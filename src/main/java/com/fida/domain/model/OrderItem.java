package com.fida.domain.model;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

public record OrderItem(
        @Nullable BigDecimal price,
        @Nullable String qty
) {}
