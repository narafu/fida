package com.fida.adapter.out.ocr;

import com.fida.domain.model.OrderItem;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GeminiVisionAdapter 테스트")
class GeminiVisionAdapterTest {

    private static final String API_KEY = "test-key";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private NotifyPort notifyPort;
    private GeminiVisionAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        notifyPort = mock(NotifyPort.class);
        adapter = new GeminiVisionAdapter(restTemplate, notifyPort,
                new GeminiQuotaTracker(Path.of(System.getProperty("java.io.tmpdir"),
                        "fida-gemini-quota-test-" + System.nanoTime() + ".json"), 20, java.time.LocalDate::now));
        ReflectionTestUtils.setField(adapter, "apiKey", API_KEY);
        ReflectionTestUtils.setField(adapter, "retryDelayMs", 0L); // 테스트에서 대기 시간 제거
    }

    @Test
    @DisplayName("Gemini 응답을 ParsedOrder로 정상 파싱한다")
    void analyze_parses_gemini_response_to_parsedOrder() {
        String geminiJson = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{"text": "{\\"buy\\":[{\\"price\\":75000,\\"qty\\":100}],\\"sell\\":[{\\"price\\":80000,\\"qty\\":\\"ALL\\"}],\\"current_cycle_start\\":1000000,\\"avg_price\\":72000,\\"holdings\\":200}"}]
                    }
                  }]
                }
                """;
        mockServer.expect(requestToUriTemplate(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}",
                API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1, 2, 3}));

        assertThat(result.buyOrders()).hasSize(1);
        assertThat(result.buyOrders().get(0).price()).isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(result.buyOrders().get(0).qty()).isEqualTo("100");
        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.sellOrders().get(0).qty()).isEqualTo("ALL");
        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(result.avgPrice()).isEqualByComparingTo(new BigDecimal("72000"));
        assertThat(result.holdings()).isEqualTo(200);
        mockServer.verify();
    }

    @Test
    @DisplayName("```json 블록으로 감싸인 응답도 파싱한다")
    void analyze_parses_json_fenced_block() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"```json\\n{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":null,\\"holdings\\":0}\\n```"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}",
                API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.buyOrders()).isEmpty();
        assertThat(result.holdings()).isZero();
    }

    @Test
    @DisplayName("holdings가 음수이면 0으로 보정한다")
    void analyze_corrects_negative_holdings_to_zero() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":72000,\\"holdings\\":-5}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}",
                API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.holdings()).isZero();
        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("holdings가 0이면 avgPrice를 null로 강제한다")
    void analyze_nullifies_avgPrice_when_holdings_zero() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":72000,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}",
                API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("holdings 프롬프트는 누적개수를 보유 수량 후보로 안내한다")
    void prompt_includes_cumulative_holdings_guidance() throws Exception {
        var promptField = GeminiVisionAdapter.class.getDeclaredField("PROMPT");
        promptField.setAccessible(true);

        String prompt = (String) promptField.get(null);

        assertThat(prompt).contains("누적개수");
    }

    @Test
    @DisplayName("Gemini 응답 텍스트가 없으면 OcrException을 던지고 텔레그램 알림을 보낸다")
    void analyze_throws_when_no_response_text() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":""}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class);

        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }

    @Test
    @DisplayName("503 오류 시 3회 재시도 후 실패하면 텔레그램 알림을 보낸다")
    void analyze_retries_three_times_on_503_then_notifies() {
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
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
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":null,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
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
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
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
    @DisplayName("sell 앞 행이 모두 null이고 마지막 행에만 값이 있어도 sell을 파싱한다")
    void analyze_parses_sell_when_only_last_row_has_data() {
        // 오늘 실제 발생 케이스: Gemini가 sell=[] 반환 → 이 테스트는 Java 필터 로직 검증
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[{\\"price\\":233.84,\\"qty\\":4},{\\"price\\":234.46,\\"qty\\":4}],\\"sell\\":[{\\"price\\":null,\\"qty\\":null},{\\"price\\":null,\\"qty\\":null},{\\"price\\":236.54,\\"qty\\":\\"ALL\\"}],\\"current_cycle_start\\":13977.43,\\"current_cycle_realized_pnl\\":200.68,\\"avg_price\\":225.746,\\"holdings\\":4}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.buyOrders()).hasSize(2);
        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.sellOrders().get(0).price()).isEqualByComparingTo(new BigDecimal("236.54"));
        assertThat(result.sellOrders().get(0).qty()).isEqualTo("ALL");
        mockServer.verify();
    }

    @Test
    @DisplayName("holdings가 0이어도 cumulative_qty가 있으면 이를 우선 사용한다")
    void analyze_prefers_cumulative_qty_when_holdings_is_zero() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[{\\"price\\":39.39,\\"qty\\":27},{\\"price\\":39.15,\\"qty\\":28}],\\"sell\\":[{\\"price\\":39.73,\\"qty\\":\\"ALL\\"}],\\"current_cycle_start\\":14063.31,\\"current_cycle_realized_pnl\\":289.54,\\"avg_price\\":35.564,\\"holdings\\":0,\\"cumulative_qty\\":27,\\"buy_qty\\":-57}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.holdings()).isEqualTo(27);
        assertThat(result.avgPrice()).isEqualByComparingTo(new BigDecimal("35.564"));
        mockServer.verify();
    }

    @Test
    @DisplayName("current_cycle_start가 null이면 자금 표의 현사이클 시작 행으로 보정한다")
    void analyze_falls_back_to_capital_row_when_current_cycle_start_is_null() {
        // 운영 사례: Gemini가 오른쪽 상단 자금 표는 읽었지만 current_cycle_start 필드만 null로 반환
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[{\\"price\\":25.95,\\"qty\\":34},{\\"price\\":26.20,\\"qty\\":34}],\\"sell\\":[{\\"price\\":26.48,\\"qty\\":33},{\\"price\\":26.33,\\"qty\\":32},{\\"price\\":27.50,\\"qty\\":30}],\\"current_cycle_start\\":null,\\"capital_rows\\":[{\\"label\\":\\"시즌1 시작원금\\",\\"value\\":10000.00},{\\"label\\":\\"현사이클 시작\\",\\"value\\":11783.18},{\\"label\\":\\"잔금\\",\\"value\\":8283.77}],\\"current_cycle_realized_pnl\\":-17.61,\\"avg_price\\":27.202,\\"cumulative_qty\\":128,\\"holdings\\":128}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("11783.18"));
        mockServer.verify();
    }

    @Test
    @DisplayName("동일 이미지에 항상 같은 결과를 얻기 위해 temperature 0을 요청에 포함한다")
    void request_includes_zero_temperature_for_deterministic_output() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":null,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andExpect(content().string(containsString("\"temperature\":0")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(new byte[]{1}));

        mockServer.verify();
    }

    @Test
    @DisplayName("current_cycle_start가 season_start_capital과 같으면 혼동 가능성을 경고 로그로 남긴다")
    void analyze_warns_when_current_cycle_start_matches_season_start_capital() {
        // 운영 사례: "현사이클 시작 $"과 "시즌1 시작원금 $"을 혼동해 같은 값(10000.00)을 반환한 케이스 재현
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":10000.00,\\"season_start_capital\\":10000.00,\\"avg_price\\":null,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("10000.00"));
        // 경고가 로그뿐 아니라 텔레그램 알림으로도 전송되는지 검증
        verify(notifyPort).notifyOcrWarning(anyString());
    }

    @Test
    @DisplayName("JPEG 매직바이트 이미지는 mime_type을 image/jpeg로 감지해 전송한다")
    void request_detects_jpeg_mime_type_from_magic_bytes() {
        // /orders/from-image는 업로드 포맷을 검증하지 않으므로, JPEG를 image/png로 잘못 표기해
        // Gemini가 "Unable to process input image" 400을 반환한 운영 사례 재현
        byte[] jpegBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":null,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andExpect(content().string(containsString("\"mime_type\":\"image/jpeg\"")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(jpegBytes));

        mockServer.verify();
    }

    @Test
    @DisplayName("PNG 시그니처가 아닌 임의 바이트는 기본값 image/png로 전송한다")
    void request_defaults_to_png_mime_type_for_unknown_bytes() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"buy\\":[],\\"sell\\":[],\\"current_cycle_start\\":null,\\"avg_price\\":null,\\"holdings\\":0}"}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andExpect(content().string(containsString("\"mime_type\":\"image/png\"")))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        adapter.analyze(List.of(new byte[]{1}));

        mockServer.verify();
    }

    @Test
    @DisplayName("503 외 서버 오류는 재시도 없이 즉시 텔레그램 알림을 보낸다")
    void analyze_notifies_immediately_on_non_503_error() {
        mockServer.expect(requestToUriTemplate(GEMINI_ENDPOINT, API_KEY))
                .andRespond(withServerError()); // 500

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class);

        mockServer.verify(); // 1회만 호출됨
        verify(notifyPort, times(1)).notifyGeminiError(any(Exception.class));
    }
}
