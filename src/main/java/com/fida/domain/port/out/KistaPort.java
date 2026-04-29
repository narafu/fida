package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

public interface KistaPort {
    void sendOrders(TradingRecord record);
}
