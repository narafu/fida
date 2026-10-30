package com.fida.domain.model;

import java.math.BigDecimal;

public record OrderItem(
        BigDecimal price, // null 허용
        String qty // null 허용
) {}
