package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface KistaPort {
    UUID sendOrders(TradingRecord record);
}
