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

        List<Order> orders = Stream.concat(
                parsedOrder.buyOrders().stream().map(item -> toOrder(item, Order.OrderDirection.BUY)),
                parsedOrder.sellOrders().stream().map(item -> toOrder(item, Order.OrderDirection.SELL))
        ).toList();

        return new FidaOrderRequest(
                record.date(),
                ticker,
                requirePositive(parsedOrder.currentCycleStart(), "currentCycleStart"),
                Objects.requireNonNullElse(parsedOrder.currentCycleRealizedPnl(), BigDecimal.ZERO),
                parsedOrder.avgPrice(),
                parsedOrder.holdings(),
                orders
        );
    }

    private static Order toOrder(OrderItem item, Order.OrderDirection direction) {
        return new Order(
                Order.OrderType.LIMIT,
                direction,
                parseQty(item.qty()),
                Objects.requireNonNullElse(item.price(), BigDecimal.ZERO)
        );
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("KISTA 전송 불가: " + field + " 값이 없거나 0 이하입니다 — Gemini OCR 파싱 결과 확인 필요 (value=" + value + ")");
        }
        return value;
    }

    private static Integer parseQty(String qty) {
        if (Objects.equals(qty, "ALL")) {
            return null;
        }
        if (qty == null || qty.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(qty.trim());
        } catch (NumberFormatException e) {
            log.warn("kista 전송을 위한 수량 파싱 실패 - 입력값: '{}'. 0개로 대체합니다.", qty, e);
            return 0;
        }
    }
}
