package com.fida.adapter.out.notify;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("TelegramAdapter 테스트")
class TelegramAdapterTest {

    private static final String BOT_TOKEN = "test-token";
    private static final String CHAT_ID = "123456";
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private TelegramAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateBuilder().build();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        adapter = new TelegramAdapter(restTemplate, BOT_TOKEN, CHAT_ID);
    }

    private TradingRecord recordWith(ParsedOrder order) {
        ScrapedPost post = new ScrapedPost("매매표", LocalDate.of(2024, 1, 15), "https://fanding.kr/1", List.of());
        return TradingRecord.of(post, order);
    }

    @Test
    @DisplayName("매수·매도·현사이클시작·평단·보유개수가 포함된 메시지가 생성된다")
    void buildMessage_includes_all_fields() {
        ParsedOrder order = new ParsedOrder(
                List.of(new OrderItem(new BigDecimal("75000"), "100")),
                List.of(new OrderItem(new BigDecimal("80000"), "50")),
                new BigDecimal("1000000"),
                new BigDecimal("72000"),
                200
        );
        TradingRecord record = recordWith(order);

        String msg = adapter.buildMessage(record);

        assertThat(msg).contains("✅ Fanding 자동 입력 완료");
        assertThat(msg).contains("📅 2024-01-15");
        assertThat(msg).contains("📈 매수:");
        assertThat(msg).contains("  1. $75000 × 100");
        assertThat(msg).contains("📉 매도:");
        assertThat(msg).contains("  1. $80000 × 50");
        assertThat(msg).contains("💵 현사이클 시작: $1000000");
        assertThat(msg).contains("📊 평단: $72000 | 보유: 200개");
    }

    @Test
    @DisplayName("수량 ALL은 '전부'로 변환된다")
    void buildMessage_qty_ALL_becomes_전부() {
        ParsedOrder order = new ParsedOrder(
                List.of(),
                List.of(new OrderItem(new BigDecimal("80000"), "ALL")),
                null, null, 0
        );
        String msg = adapter.buildMessage(recordWith(order));

        assertThat(msg).contains("  1. $80000 × 전부");
    }

    @Test
    @DisplayName("매수·매도 주문이 없으면 '없음'이 표시된다")
    void buildMessage_empty_orders_show_없음() {
        ParsedOrder order = new ParsedOrder(List.of(), List.of(), null, null, 0);
        String msg = adapter.buildMessage(recordWith(order));

        assertThat(msg).contains("📈 매수:\n  없음");
        assertThat(msg).contains("📉 매도:\n  없음");
    }

    @Test
    @DisplayName("현사이클시작·평단이 null이면 '-'로 표시된다")
    void buildMessage_null_summary_shows_dash() {
        ParsedOrder order = new ParsedOrder(List.of(), List.of(), null, null, 0);
        String msg = adapter.buildMessage(recordWith(order));

        assertThat(msg).contains("💵 현사이클 시작: $-");
        assertThat(msg).contains("📊 평단: $- |");
    }

    @Test
    @DisplayName("price가 null인 OrderItem은 메시지에서 제외된다")
    void buildMessage_null_price_items_filtered() {
        ParsedOrder order = new ParsedOrder(
                List.of(new OrderItem(null, null), new OrderItem(new BigDecimal("75000"), "100")),
                List.of(),
                null, null, 0
        );
        String msg = adapter.buildMessage(recordWith(order));

        assertThat(msg).contains("  1. $75000 × 100");
        assertThat(msg).doesNotContain("  2.");
    }

    @Test
    @DisplayName("notify 호출 시 Telegram API에 POST 요청을 보낸다")
    void notify_posts_to_telegram_api() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        ParsedOrder order = new ParsedOrder(List.of(), List.of(), null, null, 0);
        adapter.notify(recordWith(order));

        mockServer.verify();
    }
}
