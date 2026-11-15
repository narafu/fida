package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface KistaPort {
    // KISTA가 저장한 주문 ID 반환
    UUID sendOrders(TradingRecord record);
}
