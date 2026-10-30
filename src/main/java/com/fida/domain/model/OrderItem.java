package com.fida.domain.model;

import java.math.BigDecimal;

public record OrderItem(
        BigDecimal price, // null 허용
        String qty // null 허용
) {
    public static final String ALL = "ALL"; // 전량 주문 표기

    public boolean isAll() {
        return ALL.equals(qty);
    }

    // 전량이면 null, 비었거나 파싱 불가면 0, 그 외 정수 수량
    public Integer quantity() {
        if (isAll()) return null;
        if (qty == null || qty.isBlank()) return 0;
        try {
            return Integer.parseInt(qty.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
