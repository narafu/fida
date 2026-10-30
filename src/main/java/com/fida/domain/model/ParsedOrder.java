package com.fida.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record ParsedOrder(
        List<OrderItem> buyOrders,
        List<OrderItem> sellOrders,
        BigDecimal currentCycleStart, // null 허용
        BigDecimal currentCycleRealizedPnl, // null 허용
        BigDecimal avgPrice, // null 허용
        int holdings
) {}
