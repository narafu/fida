package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fida.common.CommaBigDecimalDeserializer;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
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
                    "  \"holding_qty\": 보유개수후보,\n" +
                    "  \"cumulative_qty\": 누적개수후보,\n" +
                    "  \"buy_qty\": 매수개수후보,\n" +
                    "  \"holdings\": 보유개수\n" +
                    "}\n\n" +
                    "[테이블 구조 규칙]\n" +
                    "- 이미지에 \"Limit Vwap\" 블록이 두 개 있음\n" +
                    "- 첫 번째 블록 헤더가 \"매수가\"이면 → buy 배열에 입력 (최대 3행)\n" +
                    "- 두 번째 블록 헤더가 \"매도가\"이면 → sell 배열에 입력 (최대 3행)\n" +
                    "- 각 섹션 내 행의 가격이나 수량이 \"-\"이면 해당 항목은 null\n" +
                    "- 섹션 내 빈 행(가격·수량 모두 \"-\")은 건너뜀. 값이 있는 행은 위치(첫째·둘째·셋째)에 무관하게 반드시 포함\n" +
                    "  예: 매도가 섹션이 [-/-, -/-, 236.54/남은전부]이면 → sell: [{\"price\": 236.54, \"qty\": \"ALL\"}]\n\n" +
                    "[값 추출 규칙]\n" +
                    "- buy/sell 최대 3건\n" +
                    "- 데이터 없거나 \"-\"이면 null\n" +
                    "- \"남은전부\"/\"전부\"/\"ALL\"은 \"ALL\"\n" +
                    "- 달러기호($)/콤마(,) 제거하고 숫자만, 소수점 유지\n" +
                    "- current_cycle_start: 이미지에서 정확히 \"현사이클 시작 $\" 라벨인 행의 값만 사용. 근처에 \"XXXX 시작원금 $\"(연도+시작원금) 라벨의 행이 별도로 있으며 값이 다름 — 해당 행은 사용 금지. 날짜가 아닌 금액임.\n" +
                    "- current_cycle_realized_pnl: 이미지에서 \"현사이클 실현수익 $\" 항목의 값. 음수일 수도 있음.\n" +
                    "- avg_price: 이미지 오른쪽 \"평단\" 라벨 옆 셀 값만 사용. 비어있거나 보유개수가 0이면 null. 종가/현재가 등 다른 가격 사용 금지\n" +
                    "- holding_qty: \"보유개수\" 라벨 값만 기록. 없으면 null\n" +
                    "- cumulative_qty: \"누적개수\" 라벨 값만 기록. 없으면 null\n" +
                    "- buy_qty: \"매수개수\" 라벨 값만 기록. 없으면 null\n" +
                    "- holdings: 최종 보유 수량. \"보유개수\"가 있으면 그 값, 없으면 \"누적개수\" 값 사용. \"매수개수\" 사용 금지. 반드시 0 이상의 정수이며 음수가 될 수 없음. 음수로 보이면 0으로 반환";

    private static final int MAX_RETRIES = 3;
    // 테스트에서 ReflectionTestUtils로 0으로 설정 가능
    long retryDelayMs = 60_000L;

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final NotifyPort notifyPort;
    @Value("${gemini.api-key}")
    private String apiKey;

    @Override
    public ParsedOrder analyze(List<byte[]> images) {
        Object requestBody = buildRequest(images);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(requestBody, headers);

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                GeminiResponse response = restTemplate.postForObject(ENDPOINT, entity, GeminiResponse.class, apiKey);
                String text = extractText(response);
                if (text == null || text.isBlank()) {
                    OcrException e = new OcrException("Gemini 응답 텍스트 없음");
                    notifyPort.notifyGeminiError(e);
                    throw e;
                }
                return parseOrderJson(text);
            } catch (OcrException e) {
                // 파싱 오류는 재시도 없이 즉시 rethrow (알림은 위에서 처리)
                throw e;
            } catch (HttpServerErrorException e) {
                if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                    // 503: 재시도 대상
                    lastException = e;
                    log.warn("Gemini API 503 오류 (시도 {}/{}), {}초 후 재시도", attempt, MAX_RETRIES, retryDelayMs / 1000);
                    if (attempt < MAX_RETRIES) {
                        sleepQuietly(retryDelayMs);
                    }
                } else {
                    // 503 외 서버 오류: 즉시 알림 후 실패
                    log.error("Gemini API 오류 ({})", e.getStatusCode(), e);
                    notifyPort.notifyGeminiError(e);
                    throw new OcrException("Gemini API 오류: " + e.getStatusCode(), e);
                }
            } catch (RestClientException e) {
                // 네트워크 등 통신 오류: 즉시 알림 후 실패
                log.error("Gemini API 통신 오류", e);
                notifyPort.notifyGeminiError(e);
                throw new OcrException("Gemini API 통신 오류", e);
            }
        }

        // 3회 재시도 모두 실패
        log.error("Gemini API 503 오류 {}회 재시도 후 최종 실패", MAX_RETRIES, lastException);
        notifyPort.notifyGeminiError(lastException);
        throw new OcrException("Gemini API 503 오류 " + MAX_RETRIES + "회 재시도 후 실패", lastException);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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

    private static final Pattern KOREAN_IN_NUMBER = Pattern.compile("(:\\s*-?\\d[\\d,.]*)([가-힣]+)");

    private ParsedOrder parseOrderJson(String text) {
        String jsonStr = text.trim();
        Matcher m = JSON_FENCE.matcher(jsonStr);
        if (m.find()) {
            jsonStr = m.group(1).trim();
        }
        // unquoted 숫자 뒤 한글 제거: "holdings": 7년 → "holdings": 7
        jsonStr = KOREAN_IN_NUMBER.matcher(jsonStr).replaceAll("$1");
        try {
            GeminiOrderResult raw = objectMapper.readValue(jsonStr, GeminiOrderResult.class);
            log.info(raw.toString());

            int holdings = resolveHoldings(raw);
            BigDecimal avgPrice = (holdings == 0) ? null : raw.avgPrice();
            List<OrderItem> buyOrders = toOrderItems(raw.buy());
            List<OrderItem> sellOrders = toOrderItems(raw.sell());
            // sell이 비어있으면 Gemini가 매도 주문을 누락했을 가능성 — 로그로 원인 추적
            if (sellOrders.isEmpty()) {
                log.warn("Gemini sell 파싱 결과 비어있음 — 원시 sell 데이터: {}", raw.sell());
            }

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

    private int resolveHoldings(GeminiOrderResult raw) {
        // 모델이 매수개수를 holdings로 오인식하는 운영 케이스를 방지한다.
        if (isPositive(raw.holdingQty())) {
            return raw.holdingQty();
        }
        if (isPositive(raw.cumulativeQty())) {
            return raw.cumulativeQty();
        }
        if (raw.holdings() != null) {
            return Math.max(0, raw.holdings());
        }
        return 0;
    }

    private boolean isPositive(Integer value) {
        return value != null && value > 0;
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

            @com.fasterxml.jackson.annotation.JsonProperty("holding_qty")
            Integer holdingQty,

            @com.fasterxml.jackson.annotation.JsonProperty("cumulative_qty")
            Integer cumulativeQty,

            @com.fasterxml.jackson.annotation.JsonProperty("buy_qty")
            Integer buyQty,

            Integer holdings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawOrderItem(BigDecimal price, Object qty) {}
}
