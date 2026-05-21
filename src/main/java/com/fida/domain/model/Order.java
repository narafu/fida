package com.fida.domain.model;

import java.math.BigDecimal;

public record Order(
        OrderType orderType,
        OrderDirection direction,
        Integer quantity,
        BigDecimal price
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문/
    }

    public enum OrderDirection {BUY, SELL}
}
