package com.fida.adapter.in.web;

import com.fida.domain.model.KistaResult;
import com.fida.domain.model.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record FromImageResponse(
        UUID id,
        LocalDate tradeDate,
        String ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        BigDecimal avgPrice,
        int holdings,
        List<Order> orders
) {
    static FromImageResponse from(KistaResult r) {
        if (r == null) return null;
        return new FromImageResponse(r.id(), r.tradeDate(), r.ticker(),
                r.currentCycleStart(), r.currentCycleRealizedPnl(),
                r.avgPrice(), r.holdings(), r.orders());
    }
}
