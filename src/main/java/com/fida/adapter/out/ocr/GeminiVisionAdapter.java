package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fida.common.CommaBigDecimalDeserializer;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.port.out.OcrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiVisionAdapter implements OcrPort {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}";

    private static final String PROMPT =
            "이 이미지는 주식 거래 기록입니다. 아래 JSON 형식으로만 응답하세요 (다른 텍스트 없이 JSON만):\n" +
                    "{\n" +
                    "  \"buy\": [{\"price\": 매수가격, \"qty\": 매수수량}],\n" +
                    "  \"sell\": [{\"price\": 매도가격, \"qty\": 매도수량}],\n" +
                    "  \"current_cycle_start\": 현사이클시작,\n" +
                    "  \"current_cycle_realized_pnl\": 현사이클실현수익,\n" +
                    "  \"avg_price\": 평단,\n" +
                    "  \"holdings\": 보유개수\n" +
                    "}\n\n" +
                    "[테이블 구조 규칙]\n" +
                    "- 이미지에 \"Limit Vwap\" 블록이 두 개 있음\n" +
                    "- 첫 번째 블록 헤더가 \"매수가\"이면 → buy 배열에 입력 (최대 3행)\n" +
                    "- 두 번째 블록 헤더가 \"매도가\"이면 → sell 배열에 입력 (최대 3행)\n" +
                    "- 각 섹션 내 행의 가격이나 수량이 \"-\"이면 해당 항목은 null\n\n" +
                    "[값 추출 규칙]\n" +
                    "- buy/sell 최대 3건\n" +
                    "- 데이터 없거나 \"-\"이면 null\n" +
                    "- \"남은전부\"/\"전부\"/\"ALL\"은 \"ALL\"\n" +
                    "- 달러기호($)/콤마(,) 제거하고 숫자만, 소수점 유지\n" +
                    "- current_cycle_start: 이미지에서 \"현사이클 시작 $\" 항목의 값\n" +
                    "- current_cycle_realized_pnl: 이미지에서 \"현사이클 실현수익 $\" 항목의 값. 음수일 수도 있음.\n" +
                    "- avg_price: 이미지 오른쪽 \"평단\" 라벨 옆 셀 값만 사용. 비어있거나 보유개수가 0이면 null. 종가/현재가 등 다른 가격 사용 금지\n" +
                    "- holdings: 이미지 하단 \"보유개수\" 라벨 옆 값 사용 (\"매수개수\" 사용 금지). 반드시 0 이상의 정수이며 음수가 될 수 없음. 음수로 보이면 0으로 반환";

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;
    @Value("${gemini.api-key}")
    private String apiKey;

    @Override
    public ParsedOrder analyze(List<byte[]> images) {
        Object requestBody = buildRequest(images);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(requestBody, headers);

        GeminiResponse response = restTemplate.postForObject(ENDPOINT, entity, GeminiResponse.class, apiKey);

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            throw new OcrException("Gemini 응답 텍스트 없음");
        }

        return parseOrderJson(text);
    }

    private Object buildRequest(List<byte[]> images) {
        var parts = new java.util.ArrayList<>();
        parts.add(Map.of("text", PROMPT));
        for (byte[] img : images) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", "image/png",
                    "data", Base64.getEncoder().encodeToString(img)
            )));
        }
        return Map.of("contents", List.of(Map.of("parts", parts)));
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        var candidate = response.candidates().get(0);
        if (candidate.content() == null || candidate.content().parts() == null || candidate.content().parts().isEmpty()) {
            return null;
        }
        return candidate.content().parts().get(0).text();
    }

    private ParsedOrder parseOrderJson(String text) {
        String jsonStr = text.trim();
        Matcher m = JSON_FENCE.matcher(jsonStr);
        if (m.find()) {
            jsonStr = m.group(1).trim();
        }
        try {
            GeminiOrderResult raw = objectMapper.readValue(jsonStr, GeminiOrderResult.class);
            log.info(raw.toString());

            int holdings = raw.holdings() != null ? Math.max(0, raw.holdings()) : 0;
            BigDecimal avgPrice = (holdings == 0) ? null : raw.avgPrice();
            List<OrderItem> buyOrders = toOrderItems(raw.buy());
            List<OrderItem> sellOrders = toOrderItems(raw.sell());

            return new ParsedOrder(buyOrders, sellOrders, raw.currentCycleStart(), raw.currentCycleRealizedPnl(), avgPrice, holdings);
        } catch (Exception e) {
            log.error("Gemini JSON 파싱 실패 — 원문 응답:\n{}", text, e);
            throw new OcrException("Gemini JSON 파싱 실패: " + text.substring(0, Math.min(300, text.length())), e);
        }
    }

    private List<OrderItem> toOrderItems(List<RawOrderItem> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(i -> i.price() != null || i.qty() != null)
                .map(i -> new OrderItem(i.price(), i.qty() != null ? String.valueOf(i.qty()) : null))
                .toList();
    }

    // ── Gemini API 응답 DTO ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {}

    // ── Gemini 파싱 결과 JSON DTO ───────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiOrderResult(
            List<RawOrderItem> buy,
            List<RawOrderItem> sell,

            @com.fasterxml.jackson.annotation.JsonProperty("current_cycle_start")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal currentCycleStart,

            @com.fasterxml.jackson.annotation.JsonProperty("current_cycle_realized_pnl")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal currentCycleRealizedPnl,

            @com.fasterxml.jackson.annotation.JsonProperty("avg_price")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal avgPrice,

            Integer holdings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawOrderItem(BigDecimal price, Object qty) {}
}
