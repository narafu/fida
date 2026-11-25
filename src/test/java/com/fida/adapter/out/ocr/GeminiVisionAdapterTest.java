package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fida.domain.exception.OcrException;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GeminiVisionAdapter 테스트")
class GeminiVisionAdapterTest {

    private static final String API_KEY = "test-key";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // 픽스처 → 응답 봉투 직렬화

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private NotifyPort notifyPort;
    private GeminiVisionAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        notifyPort = mock(NotifyPort.class);
        GeminiClient client = new GeminiClient(restTemplate, notifyPort,
                new GeminiQuotaTracker(Path.of(System.getProperty("java.io.tmpdir"),
                        "fida-gemini-quota-test-" + System.nanoTime() + ".json"), 20, java.time.LocalDate::now));
        ReflectionTestUtils.setField(client, "apiKey", API_KEY);
        adapter = new GeminiVisionAdapter(client, notifyPort, mock(OcrArchive.class));
        ReflectionTestUtils.setField(adapter, "retryDelayMs", 0L); // 테스트에서 대기 시간 제거
    }

    @Test
    @DisplayName("```json 블록으로 감싸인 응답도 파싱한다")
    void analyze_parses_json_fenced_block() {
        String geminiJson = envelope("fenced-empty");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.buyOrders()).isEmpty();
        assertThat(result.holdings()).isZero();
    }

    @Test
    @DisplayName("holdings 프롬프트는 누적개수를 보유 수량 후보로 안내한다")
    void prompt_includes_cumulative_holdings_guidance() {
        String prompt = GeminiClient.PROMPT;

        assertThat(prompt).contains("누적개수");
    }

    @Test
    @DisplayName("capital_rows 프롬프트는 맨 위 요약줄의 분수형 현사이클시작을 제외하도록 안내한다")
    void prompt_excludes_summary_row_current_cycle_fraction_from_capital_rows() throws Exception {
        // 운영 사례(2026-08-14): 맨 위 요약줄 "현사이클시작 | 7/10"(분수, 금액 아님)이
        // capital_rows에 잘못 섞여 들어가 "7/10"이 710으로 오파싱되고 A8에 잘못 기록됨
        String prompt = GeminiClient.PROMPT;

        assertThat(prompt).contains("분수 형태").contains("capital_rows에 절대 포함하지 말 것");
    }

    // 픽스처 본문을 Gemini generateContent 응답 봉투로 감싼다
    private static String envelope(String fixture) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("candidates", List.of(
                    Map.of("content", Map.of("parts", List.of(Map.of("text", OcrFixtures.load(fixture))))))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Gemini 응답 텍스트가 없으면 OcrException을 던지고 텔레그램 알림을 보낸다")
    void analyze_throws_when_no_response_text() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":""}]}}]}
                """;
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andExpect(header("x-goog-api-key", API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class);

        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("503 오류 시 3회 재시도 후 실패하면 텔레그램 알림을 보낸다")
    void analyze_retries_three_times_on_503_then_notifies() {
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(GEMINI_ENDPOINT))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("503");

        mockServer.verify();
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("503 오류 후 재시도에서 성공하면 정상 결과를 반환한다")
    void analyze_succeeds_after_503_retry() {
        String geminiJson = envelope("empty-holdings-zero");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.buyOrders()).isEmpty();
        mockServer.verify();
        verify(notifyPort, times(0)).notifyGeminiError(any());
    }

    @Test
    @DisplayName("429 할당량 초과는 통신 오류가 아니라 일일한도 초과로 분류한다")
    void analyze_classifies_429_quota_exceeded() {
        String quotaExceededBody = """
                {
                  "error": {
                    "code": 429,
                    "message": "You exceeded your current quota",
                    "status": "RESOURCE_EXHAUSTED"
                  }
                }
                """;
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(quotaExceededBody));

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("일일한도 초과")
                .hasMessageNotContaining("통신 오류");

        mockServer.verify();
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("프롬프트는 누적실현수익 등 금액을 수량 필드에 넣지 않도록 안내한다")
    void prompt_forbids_amounts_in_quantity_fields() {
        String prompt = GeminiClient.PROMPT;

        assertThat(prompt)
                .contains("수량 필드에 절대 넣지 말 것")
                .contains("누적실현수익")
                .contains("당일실현")
                .doesNotContain("\"holdings\"");
    }

    @Test
    @DisplayName("동일 이미지에 항상 같은 결과를 얻기 위해 temperature 0을 요청에 포함한다")
    void request_includes_zero_temperature_for_deterministic_output() {
        String geminiJson = envelope("empty-holdings-zero");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andExpect(content().string(containsString("\"temperature\":0")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(new byte[]{1}));

        mockServer.verify();
    }

    @Test
    @DisplayName("current_cycle_start가 season_start_capital과 같으면 혼동 가능성을 경고 로그로 남긴다")
    void analyze_warns_when_current_cycle_start_matches_season_start_capital() {
        // 운영 사례: "현사이클 시작 $"과 "시즌1 시작원금 $"을 혼동해 같은 값(10000.00)을 반환한 케이스 재현
        String geminiJson = envelope("season-capital-match");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("10000.00"));
        // 경고가 로그뿐 아니라 텔레그램 알림으로도 전송되는지 검증
        verify(notifyPort).notifyOcrWarning(anyString());
    }

    @Test
    @DisplayName("mime_type은 항상 image/png로 전송한다")
    void request_always_sends_png_mime_type() {
        String geminiJson = envelope("empty-holdings-zero");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andExpect(content().string(containsString("\"mime_type\":\"image/png\"")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(new byte[]{1}));

        mockServer.verify();
    }

    @Test
    @DisplayName("holdings=0인데 SELL 주문이 있으면 재요청해 완전한 응답을 얻는다")
    void analyze_retries_when_holdings_missing_with_sell_orders_present() {
        // 운영 사례(2026-08-14): 우측 자금 표를 통째로 못 읽어 holdings 관련 필드가 전부 null(=0)로
        // 파싱됐지만 SELL 주문은 정상 파싱됨 — 재요청 시 두 번째 응답에서 holdings를 확보한다
        String incomplete = envelope("sell-holdings-missing");
        String complete = envelope("sell-holdings-present");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(complete, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.holdings()).isEqualTo(35);
        mockServer.verify();
    }

    @Test
    @DisplayName("재시도해도 holdings가 계속 누락되면 마지막 응답을 그대로 반환한다")
    void analyze_returns_last_result_when_holdings_stays_missing_after_retries() {
        String incomplete = envelope("sell-holdings-missing");
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(GEMINI_ENDPOINT))
                    .andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));
        }

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.holdings()).isZero();
        assertThat(result.sellOrders()).hasSize(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("503 외 서버 오류는 재시도 없이 즉시 텔레그램 알림을 보낸다")
    void analyze_notifies_immediately_on_non_503_error() {
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withServerError()); // 500

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class);

        mockServer.verify(); // 1회만 호출됨
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("문법 오류 JSON 응답을 막기 위해 JSON 응답 모드를 요청에 포함한다")
    void request_includes_json_response_mime_type() {
        String geminiJson = envelope("minimal-empty");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andExpect(content().string(containsString("\"responseMimeType\":\"application/json\"")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(new byte[]{1}));

        mockServer.verify();
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 재요청해 다음 응답으로 파싱한다")
    void analyze_retries_on_json_parse_failure() {
        // 따옴표 없는 콤마 숫자 — JSON 문법 오류
        String invalid = envelope("invalid-fenced-comma");
        String valid = envelope("valid-quoted-comma");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(valid, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("14299.87"));
        mockServer.verify();
    }

    @Test
    @DisplayName("JSON 파싱이 모든 시도에서 실패하면 Jackson 실패 원인을 포함한 예외를 던진다")
    void analyze_throws_with_parse_reason_after_all_attempts_fail() {
        String invalid = envelope("invalid-comma");
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(GEMINI_ENDPOINT))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("Gemini JSON 파싱 실패")
                .hasMessageContaining("line 1");
        mockServer.verify();
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("holdings 누락 의심 결과를 확보한 뒤 재요청이 503으로 끝나면 확보한 결과를 반환한다")
    void analyze_returns_suspicious_result_when_later_attempts_fail_with_503() {
        String incomplete = envelope("sell-holdings-missing-minimal");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            mockServer.expect(requestTo(GEMINI_ENDPOINT))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.sellOrders()).hasSize(1);
        mockServer.verify();
        verify(notifyPort, times(0)).notifyGeminiError(any());
    }

    @Test
    @DisplayName("이미지가 없으면 Gemini를 호출하지 않고 거부한다")
    void analyze_rejects_empty_images_without_calling_gemini() {
        assertThatThrownBy(() -> adapter.analyze(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        mockServer.verify(); // 기대 요청 없음 — 호출되면 실패
    }

    @Test
    @DisplayName("수량·가격이 단위·콤마가 붙은 문자열로 와도 숫자로 파싱한다")
    void analyze_parses_quantity_and_price_strings_leniently() {
        String geminiJson = envelope("lenient-strings");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.buyOrders().get(0).price()).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(result.holdings()).isEqualTo(35);
        mockServer.verify();
    }

    @Test
    @DisplayName("닫히지 않은 코드펜스·앞뒤 설명 문장이 붙은 응답도 JSON 본문만 추출해 파싱한다")
    void analyze_extracts_json_from_unclosed_fence() {
        String geminiJson = envelope("unclosed-fence");
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.holdings()).isEqualTo(12);
        mockServer.verify();
    }

    @Test
    @DisplayName("order_tables 구조 오류는 재요청하고 다음 정상 응답을 반환한다")
    void analyze_retries_on_order_table_structure_error() {
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(envelope("order-tables-duplicate-buy"), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(GEMINI_ENDPOINT))
                .andRespond(withSuccess(envelope("order-tables-sell-only-regression"), MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.sellOrders()).hasSize(3);
        mockServer.verify(); // 정확히 2회 호출
        verify(notifyPort, times(0)).notifyGeminiError(any());
    }

    @Test
    @DisplayName("order_tables 구조 오류가 3회 모두 반복되면 결과를 채택하지 않고 OcrException을 던진다")
    void analyze_throws_when_order_table_structure_error_persists() {
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(GEMINI_ENDPOINT))
                    .andRespond(withSuccess(envelope("order-tables-duplicate-buy"), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("order_tables");

        mockServer.verify();
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }
}
