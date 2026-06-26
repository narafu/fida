package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface NotifyPort {
    void notify(TradingRecord record);

    void notifyKistaSuccess(TradingRecord record, UUID savedId);

    void notifyKistaFailure(TradingRecord record, Exception cause);

    // Gemini API 오류 알림 (재시도 소진 또는 비-503 오류)
    void notifyGeminiError(Exception cause);
}
