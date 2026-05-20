package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

public interface NotifyPort {
    void notify(TradingRecord record);

    void notifyKistaSuccess(TradingRecord record);

    void notifyKistaFailure(TradingRecord record, Throwable cause);
}
