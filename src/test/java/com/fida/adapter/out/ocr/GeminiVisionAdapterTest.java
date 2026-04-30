package com.fida.adapter.out.ocr;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GeminiVisionAdapter 테스트")
class GeminiVisionAdapterTest {

    private static final String API_KEY = "test-key";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private GeminiVisionAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        adapter = new GeminiVisionAdapter(restTemplate, API_KEY);
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
    @DisplayName("Gemini 응답 텍스트가 없으면 OcrException을 던진다")
    void analyze_throws_when_no_response_text() {
        String geminiJson = """
                {"candidates":[{"content":{"parts":[{"text":""}]}}]}
                """;
        mockServer.expect(requestToUriTemplate(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={key}",
                API_KEY))
                .andRespond(withSuccess(geminiJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze(List.of(new byte[]{1})))
                .isInstanceOf(OcrException.class);
    }
}
