package com.fida.adapter.out.kista;

import com.fida.domain.model.Order;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KistaAdapter 테스트")
class KistaAdapterTest {

    private static final String KISTA_URL = "http://kista-server";
    private static final String EXPECTED_URL = KISTA_URL + "/api/internal/fida-orders";
    private static final LocalDate TRADE_DATE = LocalDate.of(2024, 1, 15);

    @Mock RestTemplate restTemplate;
    KistaAdapter adapter;

    static final UUID SAVED_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        adapter = new KistaAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "baseUrl", KISTA_URL);
        ReflectionTestUtils.setField(adapter, "internalApiToken", "test-token");
        // 요청 데이터를 그대로 에코하는 응답으로 검증 통과 (일부 테스트에서 재정의)
        lenient().when(restTemplate.postForObject(eq(EXPECTED_URL), any(), eq(KistaOrderResponse.class)))
                .thenAnswer(inv -> {
                    FidaOrderRequest req = (FidaOrderRequest) ((HttpEntity<?>) inv.getArgument(1)).getBody();
                    return new KistaOrderResponse(SAVED_ID, req.tradeDate(), req.ticker(),
                            req.currentCycleStart(), req.currentCycleRealizedPnl(),
                            req.avgPrice(), req.holdings(), req.orders());
                });
    }

    private TradingRecord recordWith(List<OrderItem> buy, List<OrderItem> sell) {
        return recordWith(buy, sell, null, null, null, 0);
    }

    private TradingRecord recordWith(List<OrderItem> buy, List<OrderItem> sell,
                                     BigDecimal cycleStart, BigDecimal realizedPnl,
                                     BigDecimal avgPrice, int holdings) {
        var post = new ScrapedPost("제목", TRADE_DATE, "https://example.com", List.of());
        var order = new ParsedOrder(buy, sell, cycleStart, realizedPnl, avgPrice, holdings);
        return TradingRecord.of(post, order);
    }

    @SuppressWarnings("unchecked")
    private FidaOrderRequest capturedRequest() {
        var captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq(EXPECTED_URL), captor.capture(), eq(KistaOrderResponse.class));
        return (FidaOrderRequest) captor.getValue().getBody();
    }

    @Test
    @DisplayName("항상 /api/internal/fida-orders에 1회 POST한다")
    void sendOrders_posts_exactly_once_to_fida_endpoint() {
        adapter.sendOrders(recordWith(List.of(), List.of()));
        capturedRequest();
    }

    @Test
    @DisplayName("KISTA 응답의 id를 반환한다")
    void sendOrders_returns_saved_id_from_response() {
        var result = adapter.sendOrders(recordWith(List.of(), List.of()));
        assertThat(result.id()).isEqualTo(SAVED_ID);
    }

    @Test
    @DisplayName("ticker는 SOXL, tradeDate는 TradingRecord.date()가 된다")
    void sendOrders_maps_ticker_and_tradeDate() {
        adapter.sendOrders(recordWith(List.of(), List.of()));
        var req = capturedRequest();
        assertThat(req.ticker()).isEqualTo("SOXL");
        assertThat(req.tradeDate()).isEqualTo(TRADE_DATE);
    }

    @Test
    @DisplayName("매수 주문은 BUY, 매도 주문은 SELL direction으로 orders에 포함된다")
    void sendOrders_maps_buy_and_sell_to_orders_with_correct_direction() {
        var buy = new OrderItem(new BigDecimal("10.50"), "3");
        var sell = new OrderItem(new BigDecimal("20.00"), "5");

        adapter.sendOrders(recordWith(List.of(buy), List.of(sell)));
        var req = capturedRequest();

        assertThat(req.orders()).hasSize(2);
        assertThat(req.orders().get(0).direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(req.orders().get(0).price()).isEqualByComparingTo("10.50");
        assertThat(req.orders().get(0).quantity()).isEqualTo(3);
        assertThat(req.orders().get(1).direction()).isEqualTo(Order.OrderDirection.SELL);
        assertThat(req.orders().get(1).price()).isEqualByComparingTo("20.00");
        assertThat(req.orders().get(1).quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("orders 배열은 BUY가 먼저, SELL이 나중 순서다")
    void sendOrders_buy_orders_precede_sell_orders() {
        var buy1 = new OrderItem(new BigDecimal("10.00"), "1");
        var buy2 = new OrderItem(new BigDecimal("11.00"), "2");
        var sell = new OrderItem(new BigDecimal("12.00"), "3");

        adapter.sendOrders(recordWith(List.of(buy1, buy2), List.of(sell)));
        var req = capturedRequest();

        assertThat(req.orders()).hasSize(3);
        assertThat(req.orders().get(0).direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(req.orders().get(1).direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(req.orders().get(2).direction()).isEqualTo(Order.OrderDirection.SELL);
    }

    @Test
    @DisplayName("qty가 null이거나 파싱 불가능하면 quantity=0이 된다")
    void sendOrders_qty_null_or_unparseable_becomes_zero() {
        var nullQty = new OrderItem(new BigDecimal("15.00"), null);
        var badQty = new OrderItem(new BigDecimal("15.00"), "N/A");

        adapter.sendOrders(recordWith(List.of(nullQty, badQty), List.of()));
        var req = capturedRequest();

        assertThat(req.orders().get(0).quantity()).isZero();
        assertThat(req.orders().get(1).quantity()).isZero();
    }

    @Test
    @DisplayName("qty가 \"ALL\"이면 quantity=null이 된다")
    void sendOrders_qty_jeonbu_becomes_null() {
        var jeonbu = new OrderItem(new BigDecimal("15.00"), "ALL");

        adapter.sendOrders(recordWith(List.of(jeonbu), List.of()));
        var req = capturedRequest();

        assertThat(req.orders().get(0).quantity()).isNull();
    }

    @Test
    @DisplayName("price가 null이면 ZERO로 fallback된다")
    void sendOrders_null_price_falls_back_to_zero() {
        var nullPrice = new OrderItem(null, "5");

        adapter.sendOrders(recordWith(List.of(nullPrice), List.of()));
        var req = capturedRequest();

        assertThat(req.orders().get(0).price()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("currentCycleStart와 currentCycleRealizedPnl이 null이면 ZERO로 fallback된다")
    void sendOrders_null_cycle_values_fall_back_to_zero() {
        adapter.sendOrders(recordWith(List.of(), List.of(), null, null, null, 0));
        var req = capturedRequest();

        assertThat(req.currentCycleStart()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(req.currentCycleRealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("currentCycleStart, realizedPnl, avgPrice, holdings가 올바르게 매핑된다")
    void sendOrders_maps_cycle_and_holding_fields() {
        var cycleStart = new BigDecimal("1000");
        var realizedPnl = new BigDecimal("-200");
        var avgPrice = new BigDecimal("72.50");

        adapter.sendOrders(recordWith(List.of(), List.of(), cycleStart, realizedPnl, avgPrice, 150));
        var req = capturedRequest();

        assertThat(req.currentCycleStart()).isEqualByComparingTo(cycleStart);
        assertThat(req.currentCycleRealizedPnl()).isEqualByComparingTo(realizedPnl);
        assertThat(req.avgPrice()).isEqualByComparingTo(avgPrice);
        assertThat(req.holdings()).isEqualTo(150);
    }

    @Test
    @DisplayName("orders가 비어 있어도 1회 POST하고 orders 리스트는 비어 있다")
    void sendOrders_posts_once_even_with_empty_orders() {
        adapter.sendOrders(recordWith(List.of(), List.of()));
        var req = capturedRequest();
        assertThat(req.orders()).isEmpty();
    }

    @Test
    @DisplayName("응답이 null이면 IllegalStateException을 던진다")
    void sendOrders_throws_when_response_is_null() {
        when(restTemplate.postForObject(eq(EXPECTED_URL), any(), eq(KistaOrderResponse.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> adapter.sendOrders(recordWith(List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("응답 tradeDate가 다르면 IllegalStateException을 던진다")
    void sendOrders_throws_when_response_tradeDate_mismatches() {
        when(restTemplate.postForObject(eq(EXPECTED_URL), any(), eq(KistaOrderResponse.class)))
                .thenReturn(new KistaOrderResponse(SAVED_ID, TRADE_DATE.plusDays(1),
                        "SOXL", BigDecimal.ZERO, BigDecimal.ZERO, null, 0, List.of()));

        assertThatThrownBy(() -> adapter.sendOrders(recordWith(List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tradeDate");
    }

    @Test
    @DisplayName("응답 orders 수가 다르면 IllegalStateException을 던진다")
    void sendOrders_throws_when_response_orders_size_mismatches() {
        var extraOrder = new Order(Order.OrderType.LIMIT, Order.OrderDirection.BUY, 1, BigDecimal.TEN);
        when(restTemplate.postForObject(eq(EXPECTED_URL), any(), eq(KistaOrderResponse.class)))
                .thenReturn(new KistaOrderResponse(SAVED_ID, TRADE_DATE,
                        "SOXL", BigDecimal.ZERO, BigDecimal.ZERO, null, 0, List.of(extraOrder)));

        assertThatThrownBy(() -> adapter.sendOrders(recordWith(List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders");
    }
}
