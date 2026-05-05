package com.fida.adapter.out.kista;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KistaAdapter 테스트")
class KistaAdapterTest {

    @Mock RestTemplate restTemplate;

    KistaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KistaAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "kistaUrl", "http://kista-server");
    }

    private TradingRecord recordWith(List<OrderItem> buyOrders, List<OrderItem> sellOrders) {
        var post = new ScrapedPost("제목", LocalDate.now(), "https://example.com", List.of());
        var order = new ParsedOrder(buyOrders, sellOrders, null, null, 0);
        return TradingRecord.of(post, order);
    }

    @Test
    @DisplayName("매수 주문을 direction=BUY로 /api/orders/fida에 POST한다")
    void sendOrders_posts_buy_orders_with_BUY_direction() {
        var buy = new OrderItem(new BigDecimal("10.50"), "3");
        var record = recordWith(List.of(buy), List.of());

        adapter.sendOrders(record);

        var captor = ArgumentCaptor.forClass(FidaOrderRequest.class);
        verify(restTemplate).postForObject(contains("/api/orders/fida"), captor.capture(), eq(Void.class));
        var req = captor.getValue();
        assertThat(req.direction()).isEqualTo("BUY");
        assertThat(req.symbol()).isEqualTo("SOXL");
        assertThat(req.price()).isEqualByComparingTo("10.50");
        assertThat(req.qty()).isEqualTo(3);
    }

    @Test
    @DisplayName("매도 주문을 direction=SELL로 /api/orders/fida에 POST한다")
    void sendOrders_posts_sell_orders_with_SELL_direction() {
        var sell = new OrderItem(new BigDecimal("20.00"), "5");
        var record = recordWith(List.of(), List.of(sell));

        adapter.sendOrders(record);

        var captor = ArgumentCaptor.forClass(FidaOrderRequest.class);
        verify(restTemplate).postForObject(contains("/api/orders/fida"), captor.capture(), eq(Void.class));
        assertThat(captor.getValue().direction()).isEqualTo("SELL");
    }

    @Test
    @DisplayName("price가 null인 항목은 POST 호출하지 않는다")
    void sendOrders_skips_items_with_null_price() {
        var nullPrice = new OrderItem(null, "2");
        var record = recordWith(List.of(nullPrice), List.of());

        adapter.sendOrders(record);

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("매수·매도 주문이 모두 비어있으면 POST 호출이 없다")
    void sendOrders_does_nothing_for_empty_orders() {
        var record = recordWith(List.of(), List.of());

        adapter.sendOrders(record);

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("qty가 null이면 요청의 qty도 null이다")
    void sendOrders_passes_null_qty_when_item_qty_is_null() {
        var item = new OrderItem(new BigDecimal("15.00"), null);
        var record = recordWith(List.of(item), List.of());

        adapter.sendOrders(record);

        var captor = ArgumentCaptor.forClass(FidaOrderRequest.class);
        verify(restTemplate).postForObject(anyString(), captor.capture(), eq(Void.class));
        assertThat(captor.getValue().qty()).isNull();
    }

    @Test
    @DisplayName("qty 파싱 실패 시 null로 처리한다")
    void sendOrders_passes_null_qty_when_qty_is_not_parseable() {
        var item = new OrderItem(new BigDecimal("15.00"), "N/A");
        var record = recordWith(List.of(item), List.of());

        adapter.sendOrders(record);

        var captor = ArgumentCaptor.forClass(FidaOrderRequest.class);
        verify(restTemplate).postForObject(anyString(), captor.capture(), eq(Void.class));
        assertThat(captor.getValue().qty()).isNull();
    }

    @Test
    @DisplayName("매수 2건 + 매도 1건이면 POST가 총 3번 호출된다")
    void sendOrders_calls_post_once_per_valid_order_item() {
        var buy1 = new OrderItem(new BigDecimal("10.00"), "1");
        var buy2 = new OrderItem(new BigDecimal("11.00"), "2");
        var sell1 = new OrderItem(new BigDecimal("12.00"), "3");
        var record = recordWith(List.of(buy1, buy2), List.of(sell1));

        adapter.sendOrders(record);

        verify(restTemplate, times(3)).postForObject(anyString(), any(), eq(Void.class));
    }
}
