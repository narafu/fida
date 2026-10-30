package com.fida.domain.port.out;

import com.fida.domain.model.TradingRecord;

import java.util.UUID;

public interface NotifyPort {
    // 매매 알림 — 실패 시 NotifyException 전파 (호출측이 실패를 집계)
    void notify(TradingRecord record);

    // 이하 부가 알림은 best-effort — 실패해도 예외를 던지지 않음
    void notifyKistaSuccess(TradingRecord record, UUID savedId);

    void notifyKistaFailure(TradingRecord record, Exception cause);

    // Gemini API 오류 알림 (재시도 소진 또는 비-503 오류)
    void notifyGeminiError(Exception cause);

    // 전체 실행 실패 알림 (스크래핑·시트·OCR 등 최상위 예외)
    void notifyApplicationFailure(String stage, Exception cause);

    // OCR 파싱 결과 의심 시 경고 알림 (전송은 차단하지 않음)
    void notifyOcrWarning(String warning);

    // Gemini API 일일한도 알림
    void notifyGeminiQuota(int remaining, int limit);
}
