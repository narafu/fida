package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fida.domain.exception.OcrException;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// Gemini generateContent 1회 호출 — 재시도 판단은 호출자(GeminiVisionAdapter) 몫
@Slf4j
@Component
@RequiredArgsConstructor
class GeminiClient {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    static final String PROMPT = """
            이 이미지는 주식 매매표입니다. 아래 JSON 형식으로만 응답하세요. <> 안은 값의 타입 설명이며, 값이 없으면 null:
            {
              "buy": [{"price": <숫자>, "qty": <정수 또는 "ALL">}],
              "sell": [{"price": <숫자>, "qty": <정수 또는 "ALL">}],
              "sell_rows": [{"price": <숫자|null>, "qty": <정수|"ALL"|null>}],
              "current_cycle_start": <금액|null>,
              "season_start_capital": <금액|null>,
              "capital_rows": [{"label": <라벨 문자열>, "value": <금액>}],
              "performance_rows": [{"label": <라벨 문자열>, "value": <숫자>}],
              "current_cycle_realized_pnl": <금액|null>,
              "avg_price": <숫자|null>,
              "holding_qty": <정수|null>,
              "cumulative_qty": <정수|null>,
              "buy_qty": <정수|null>
            }

            [공통 원칙]
            - 매매표 레이아웃은 날짜마다 달라질 수 있음. 각 항목은 화면 위치가 아니라 라벨 텍스트로 찾을 것
            - 해당 라벨이 이미지에 없으면 비슷한 다른 항목으로 추측하지 말고 null
            - 금액과 수량을 구분할 것: 수량 필드(qty, holding_qty, cumulative_qty, buy_qty)는 항상 정수 개수. "$"가 붙은 금액, 소수점이 있는 값, "%" 비율은 수량 필드에 절대 넣지 말 것
            - 라벨에 "누적"이 들어가도 "누적실현수익"·"총 수익률"은 금액/비율이며 수량(누적개수)이 아님
            - 데이터가 없거나 "-"이면 null
            - "남은전부"/"전부"/"ALL"은 "ALL"
            - 달러기호($)/콤마(,) 제거하고 숫자만, 소수점 유지

            [매수·매도 표]
            - 이미지에 "Limit Vwap" 블록이 두 개 있음
            - 첫 번째 블록 헤더가 "매수가"이면 → buy 배열에 입력 (최대 3행)
            - 두 번째 블록 헤더가 "매도가"이면 → sell 배열에 입력 (최대 3행)
            - sell_rows는 매도가 표의 물리적 3개 행을 위에서부터 순서대로 반드시 모두 입력. 빈 행도 생략하지 말고 {"price": null, "qty": null}로 입력해 배열 길이를 정확히 3으로 유지
            - 각 행의 가격이나 수량이 "-"이면 해당 항목은 null
            - buy/sell에서 빈 행(가격·수량 모두 "-")은 건너뜀. 값이 있는 행은 위치(첫째·둘째·셋째)에 무관하게 반드시 포함
              예: 매도가 섹션이 [-/-, -/-, 236.54/남은전부]이면 → sell: [{"price": 236.54, "qty": "ALL"}]
            - sell 배열: 반드시 "매도가" 헤더 아래 두 열(매도가격·개수)만 사용. 종가/평단/실현수익 등 다른 표의 숫자는 절대 포함 금지. 매도가·개수 모두 비어있으면 sell: []

            [자금·성과 항목]
            - current_cycle_start: 정확히 "현사이클 시작 $" 라벨(달러기호 포함) 행의 금액만 사용. "XXXX 시작원금 $"(연도·시즌+시작원금) 라벨 행이 별도로 있을 수 있으며 값이 다름 — 해당 행은 사용 금지. "현사이클시작"(공백·달러기호 없음, 보통 좌측 상단) 라벨 행은 사이클 시작 날짜(M/D 형식, 예: 7/10)이며 전혀 다른 항목 — 반드시 제외. 날짜가 아닌 금액임.
            - season_start_capital: "XXXX 시작원금 $"/"시즌N 시작원금" 라벨 행의 금액. 그런 행이 없고 상단 안내문에 "원금 N 달러로 ... 시작" 문장이 있으면 그 N. 둘 다 없으면 null. current_cycle_start와 혼동 검증용이므로 별도로 기록
            - capital_rows: "현사이클 시작 $"·"잔금 $"·"시작원금" 등 자금 라벨 행을 표 위치(오른쪽 상단·하단 등)와 무관하게 보이는 그대로 모두 기록. 예: [{"label":"시즌1 시작원금","value":10000.00},{"label":"현사이클 시작","value":11783.18},{"label":"잔금","value":8283.77}]. "현사이클 차수"(예: 31차)는 금액이 아니므로 제외. 이미지 맨 위 요약줄·좌측 상단의 "현사이클시작 | N/M"(분수 형태, 금액 아님) 또는 "현사이클시작 | 7/10"(날짜 형태, 금액 아님)은 자금 표와 다른 별개 항목이므로 capital_rows에 절대 포함하지 말 것
            - performance_rows: 성과 관련 라벨 행(종가·평단/보유 평단·당일실현·현사이클 실현수익·시즌N 실현수익·시즌N 실현수익률·총 수익률·누적실현수익 등)을 보이는 그대로 모두 기록. 값이 0.00이어도 그대로 기록(생략 금지), 값이 비어있으면 value는 null. 예: [{"label":"종가","value":65.23},{"label":"평단","value":64.684},{"label":"당일실현","value":null},{"label":"현사이클 실현수익","value":0.00},{"label":"누적실현수익","value":4347.11}]
            - current_cycle_realized_pnl: performance_rows와 별개로 정확히 "현사이클 실현수익 $" 라벨 행의 값만 사용(참고용 필드, performance_rows가 신뢰 소스). 인접한 "당일실현 $"(당일분)·"누적실현수익"·"총 수익률"·"2026 실현수익 $"(연간 누적)·"연간 실현수익 $"·"시즌N 실현수익 $"·"시즌N 실현수익률" 등 다른 실현수익/수익률 항목은 절대 사용 금지. 값이 0.00이어도 그대로 사용. 음수일 수도 있음.
            - avg_price: "평단" 또는 "보유 평단" 라벨 옆 셀 값만 사용. 비어있거나 보유 개수가 0이면 null. 종가/현재가 등 다른 가격 사용 금지

            [보유 수량 항목]
            - holding_qty: 정확히 "보유개수" 또는 "현재 보유 개수" 라벨 행의 값. 라벨이 보이는데 값이 0이면 0, 라벨 자체가 없으면 반드시 null(0으로 채우지 말 것). "매수개수"/"직전 매매 개수" 값을 복사하지 말 것
            - cumulative_qty: 정확히 "누적개수" 라벨 행의 값(양수 정수). "N 배수 예시" 섹션에도 누적개수가 표시되는 경우가 있으나 그 섹션은 무시. "누적개수" 라벨이 없으면 반드시 null — "누적실현수익" 등 금액 행의 숫자를 대신 쓰지 말 것
            - buy_qty: 정확히 "매수개수" 라벨 행의 값. 음수일 수 있음. 없으면 null""";

    // 잔여 한도가 이 값 이하로 떨어졌을 때만 텔레그램 알림 — 매 호출마다 알리면 정상 성공을 오류로 오인하기 쉬움
    private static final int QUOTA_WARNING_THRESHOLD = 5;

    private final RestTemplate restTemplate; // Gemini HTTP 호출
    private final NotifyPort notifyPort; // 오류·한도 알림
    private final GeminiQuotaTracker quotaTracker; // 일일 사용량 기록
    @Value("${gemini.api-key}")
    private String apiKey; // Gemini API 키

    // 응답 텍스트 반환. 503은 HttpServerErrorException 그대로 전파(재시도 대상), 그 외 오류는 알림 후 OcrException
    String generate(List<byte[]> images) {
        // Gemini 400 "Unable to process input image" 진단용 — 실제 전송 바이트 크기·헤더 확인
        logImageDiagnostics(images);
        GeminiResponse response;
        try {
            response = restTemplate.postForObject(ENDPOINT, buildEntity(images), GeminiResponse.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Gemini 무료 티어 일일 요청 한도 초과 — 네트워크 장애가 아니므로 원인을 명확히 노출
            log.warn("Gemini API 일일한도 초과 (429): {}", e.getResponseBodyAsString());
            notifyPort.notifyGeminiError(e);
            throw new OcrException("Gemini API 일일한도 초과", e);
        } catch (HttpServerErrorException e) {
            if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw e;
            }
            // 503 외 서버 오류: 즉시 알림 후 실패
            log.error("Gemini API 오류 ({})", e.getStatusCode(), e);
            notifyPort.notifyGeminiError(e);
            throw new OcrException("Gemini API 오류: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // 네트워크 등 통신 오류: 즉시 알림 후 실패
            log.error("Gemini API 통신 오류", e);
            notifyPort.notifyGeminiError(e);
            throw new OcrException("Gemini API 통신 오류", e);
        } finally {
            // 성공·실패 무관하게 요청 1회로 기록 — 503이 한도에 포함되는지 불확실해 보수적으로 센다
            recordQuota();
        }

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            OcrException e = new OcrException("Gemini 응답 텍스트 없음");
            notifyPort.notifyGeminiError(e);
            throw e;
        }
        return text;
    }

    private HttpEntity<Object> buildEntity(List<byte[]> images) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // API 키는 헤더로 전송 — URL 쿼리에 두면 RestClientException 메시지에 포함돼 로그·텔레그램으로 유출됨
        headers.set("x-goog-api-key", apiKey);
        return new HttpEntity<>(buildRequest(images), headers);
    }

    private Object buildRequest(List<byte[]> images) {
        var parts = new ArrayList<>();
        parts.add(Map.of("text", PROMPT));
        for (byte[] img : images) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", detectMimeType(img),
                    "data", Base64.getEncoder().encodeToString(img)
            )));
        }
        // temperature 0: 동일 이미지 재요청 시 결과가 달라지는 비결정적 응답 방지
        // responseMimeType JSON: 코드펜스·따옴표 없는 콤마 숫자(14,299.87) 등 문법 오류 JSON 응답 차단
        return Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json")
        );
    }

    private void logImageDiagnostics(List<byte[]> images) {
        for (int i = 0; i < images.size(); i++) {
            byte[] img = images.get(i);
            int headerLen = Math.min(img.length, 12);
            StringBuilder hex = new StringBuilder();
            for (int b = 0; b < headerLen; b++) {
                hex.append(String.format("%02X ", img[b]));
            }
            log.info("Gemini 이미지 진단 [{}/{}] bytes={} header={}mime={}",
                    i + 1, images.size(), img.length, hex, detectMimeType(img));
        }
    }

    // /orders/from-image는 업로드 파일 포맷을 검증하지 않으므로, 매직바이트로 실제 포맷을 감지해
    // Gemini에 잘못된 mime_type(예: JPEG를 image/png로 표기)을 보내 400 오류가 나는 것을 방지
    private String detectMimeType(byte[] img) {
        if (img.length >= 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 && (img[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (img.length >= 6 && img[0] == 'G' && img[1] == 'I' && img[2] == 'F' && img[3] == '8') {
            return "image/gif";
        }
        if (img.length >= 12 && img[0] == 'R' && img[1] == 'I' && img[2] == 'F' && img[3] == 'F'
                && img[8] == 'W' && img[9] == 'E' && img[10] == 'B' && img[11] == 'P') {
            return "image/webp";
        }
        // PNG 시그니처 및 그 외(테스트 더미 바이트 등)는 기존 동작 유지 위해 기본값 image/png
        return "image/png";
    }

    private void recordQuota() {
        try {
            GeminiQuotaTracker.QuotaStatus status = quotaTracker.recordRequest();
            // 매 호출마다 알리면 정상 성공 알림을 오류로 오인하기 쉬워, 실제로 소진에 가까울 때만 알림
            if (status.remaining() <= QUOTA_WARNING_THRESHOLD) {
                notifyPort.notifyGeminiQuota(status.remaining(), status.limit());
            }
        } catch (Exception e) {
            log.warn("Gemini 일일한도 알림 실패: {}", e.getMessage());
        }
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

    // ── Gemini API 응답 DTO ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text) {}
}
