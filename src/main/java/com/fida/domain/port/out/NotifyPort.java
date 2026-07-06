package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface NotifyPort {
    void notify(TradingRecord record);

    void notifyKistaSuccess(TradingRecord record, UUID savedId);

    void notifyKistaFailure(TradingRecord record, Exception cause);

    // Gemini API 오류 알림 (재시도 소진 또는 비-503 오류)
    void notifyGeminiError(Exception cause);

    // 전체 실행 실패 알림 (스크래핑·시트·OCR 등 최상위 예외)
    void notifyApplicationFailure(String stage, Exception cause);

    // Gemini API 일일한도 알림
    void notifyGeminiQuota(int remaining, int limit);
}
