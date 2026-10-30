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
) {
    // OCR 파싱 결과가 모순되면 외부 전송 전에 차단
    public void requireSubmittable() {
        if (holdings == 0 && !sellOrders.isEmpty()) {
            throw new IllegalArgumentException("KISTA 전송 불가: holdings=0 인데 SELL 주문이 존재합니다 — Gemini OCR 파싱 결과 확인 필요");
        }
        if (currentCycleStart == null || currentCycleStart.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("KISTA 전송 불가: currentCycleStart 값이 없거나 0 이하입니다 — Gemini OCR 파싱 결과 확인 필요 (value=" + currentCycleStart + ")");
        }
    }
}
