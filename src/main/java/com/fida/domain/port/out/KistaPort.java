package com.fida.domain.port.out;

import com.fida.domain.model.KistaResult;
import com.fida.domain.model.TradingRecord;

public interface KistaPort {
    KistaResult sendOrders(TradingRecord record);
}
