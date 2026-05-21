package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface NotifyPort {
    void notify(TradingRecord record);

    void notifyKistaSuccess(TradingRecord record, UUID savedId);

    void notifyKistaFailure(TradingRecord record, Exception cause);
}
