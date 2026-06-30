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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        adapter = new GeminiVisionAdapter(restTemplate, notifyPort);
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
