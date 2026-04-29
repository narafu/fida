package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

public interface SheetPort {
    void update(TradingRecord record);
}
