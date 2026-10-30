package com.fida.adapter.out.kista;

import com.fida.domain.model.Order;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
record FidaOrderRequest(
        LocalDate tradeDate,
        String ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        BigDecimal avgPrice,
        int holdings,
        List<Order> orders
) {
    public static FidaOrderRequest of(TradingRecord record, String ticker) {
        var parsedOrder = record.order();

        // OCR 파싱 결과가 모순되면 외부 API까지 보내지 않고 즉시 차단한다.
        parsedOrder.requireSubmittable();

        List<Order> orders = Stream.concat(
                parsedOrder.buyOrders().stream().map(item -> toOrder(item, Order.OrderDirection.BUY)),
                parsedOrder.sellOrders().stream().map(item -> toOrder(item, Order.OrderDirection.SELL))
        ).toList();

        return new FidaOrderRequest(
                record.date(),
                ticker,
                parsedOrder.currentCycleStart(),
                Objects.requireNonNullElse(parsedOrder.currentCycleRealizedPnl(), BigDecimal.ZERO),
                parsedOrder.avgPrice(),
                parsedOrder.holdings(),
                orders
        );
    }

    private static Order toOrder(OrderItem item, Order.OrderDirection direction) {
        Integer quantity = item.quantity();
        // 비파싱 수량은 0개 주문으로 대체되므로 원문을 로그로 남겨 OCR 오독을 추적
        if (Integer.valueOf(0).equals(quantity) && item.qty() != null && !item.qty().isBlank() && !item.qty().trim().equals("0")) {
            log.warn("kista 전송을 위한 수량 파싱 실패 - 입력값: '{}'. 0개로 대체합니다.", item.qty());
        }
        return new Order(
                Order.OrderType.LIMIT,
                direction,
                quantity,
                Objects.requireNonNullElse(item.price(), BigDecimal.ZERO)
        );
    }
}
