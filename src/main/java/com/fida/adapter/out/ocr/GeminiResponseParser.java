package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fida.common.CommaBigDecimalDeserializer;
import com.fida.domain.exception.OcrException;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Gemini 응답 텍스트 → GeminiOrderResult 역직렬화 (보정 규칙은 OcrResultReconciler)
@Slf4j
final class GeminiResponseParser {

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private static final Pattern KOREAN_IN_NUMBER = Pattern.compile("(:\\s*-?\\d[\\d,.]*)([가-힣]+)");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private GeminiResponseParser() {}

    static GeminiOrderResult parse(String text) {
        String jsonStr = extractJson(text);
        // unquoted 숫자 뒤 한글 제거: "holding_qty": 7년 → "holding_qty": 7
        jsonStr = KOREAN_IN_NUMBER.matcher(jsonStr).replaceAll("$1");
        // current_cycle_start 조용한 null 처리(날짜 문자열 등 숫자 변환 실패) 원인 진단용
        log.info("Gemini 원문 JSON 응답: {}", jsonStr);
        try {
            GeminiOrderResult raw = objectMapper.readValue(jsonStr, GeminiOrderResult.class);
            log.info(raw.toString());
            return raw;
        } catch (JsonProcessingException e) {
            log.error("Gemini JSON 파싱 실패 — 원문 응답:\n{}", text, e);
            // 텔레그램 알림에서 실패 원인·위치를 바로 확인할 수 있도록 Jackson 원인을 응답 앞부분보다 먼저 노출
            throw new GeminiJsonParseException("Gemini JSON 파싱 실패: " + describe(e)
                    + " / 응답 앞부분: " + text.substring(0, Math.min(200, text.length())), e);
        }
    }

    // 코드펜스 내부 우선, 펜스가 닫히지 않았거나 앞뒤에 설명 문장이 붙은 경우 첫 '{'~마지막 '}' 구간 사용
    private static String extractJson(String text) {
        String trimmed = text.trim();
        Matcher m = JSON_FENCE.matcher(trimmed);
        if (m.find()) {
            return m.group(1).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return (start >= 0 && end > start) ? trimmed.substring(start, end + 1) : trimmed;
    }

    // Jackson 예외에서 원인 메시지 + 줄/열 위치만 추출 (전체 스택·소스 덤프 제외)
    private static String describe(JsonProcessingException e) {
        JsonLocation loc = e.getLocation();
        String where = loc != null ? " (line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ")" : "";
        return e.getOriginalMessage() + where;
    }

    // 응답 JSON 형식 오류 — HTTP 오류와 달리 재요청으로 회복 가능해 별도 타입으로 구분
    static class GeminiJsonParseException extends OcrException {
        GeminiJsonParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Gemini 파싱 결과 JSON DTO ───────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiOrderResult(
            List<RawOrderItem> buy,
            List<RawOrderItem> sell,

            @JsonProperty("sell_rows")
            List<RawOrderItem> sellRows,

            @JsonProperty("current_cycle_start")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal currentCycleStart,

            @JsonProperty("season_start_capital")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal seasonStartCapital,

            @JsonProperty("capital_rows")
            List<LabeledValue> capitalRows,

            @JsonProperty("performance_rows")
            List<LabeledValue> performanceRows,

            @JsonProperty("current_cycle_realized_pnl")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal currentCycleRealizedPnl,

            @JsonProperty("avg_price")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal avgPrice,

            // 소수 값(금액 오독)을 Integer로 조용히 잘라 받지 않도록 BigDecimal로 받아 수량 변환 시 검증
            @JsonProperty("holding_qty")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal holdingQty,

            @JsonProperty("cumulative_qty")
            @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
            BigDecimal cumulativeQty
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawOrderItem(@JsonDeserialize(using = CommaBigDecimalDeserializer.class) BigDecimal price,
                        Object qty) {}

    // capital_rows·performance_rows 공통 라벨-값 행
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabeledValue(String label,
                        @JsonDeserialize(using = CommaBigDecimalDeserializer.class)
                        BigDecimal value) {}
}
