package com.fida.domain.model;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

public record ParsedOrder(
        List<OrderItem> buyOrders,
        List<OrderItem> sellOrders,
        @Nullable BigDecimal currentCycleStart,
        @Nullable BigDecimal avgPrice,
        int holdings
) {}
