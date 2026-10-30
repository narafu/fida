package com.fida.adapter.out.ocr;

import com.fida.adapter.out.ocr.GeminiResponseParser.GeminiJsonParseException;
import com.fida.domain.exception.OcrException;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;

// OCR 재시도 오케스트레이션 — 호출(GeminiClient)·파싱(GeminiResponseParser)·보정(OcrResultReconciler)을 묶는다
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiVisionAdapter implements OcrPort {

    private static final int MAX_RETRIES = 3;
    // 테스트에서 ReflectionTestUtils로 0으로 설정 가능
    long retryDelayMs = 60_000L;

    private final GeminiClient client; // Gemini 1회 호출
    private final NotifyPort notifyPort; // OCR 경고·오류 알림

    @Override
    public ParsedOrder analyze(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("OCR 대상 이미지가 없습니다");
        }

        // 503·JSON 파싱 실패는 3회 예산 안에서 재시도, 그 외 오류는 GeminiClient가 알림 후 즉시 전파
        Exception lastFailure = null;
        // holdings 누락 의심으로 재요청한 직전 결과 — 이후 시도가 모두 실패하면 이 값을 반환
        OcrResultReconciler.Result suspicious = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var result = OcrResultReconciler.reconcile(GeminiResponseParser.parse(client.generate(images)));
                // 운영 사례(2026-08-14): holdings 관련 필드를 통째로 누락한 채 SELL 주문만 파싱되는 경우가 있어,
                // 남은 재시도가 있으면 Gemini에 다시 요청해 완전한 응답을 한 번 더 시도한다
                if (isHoldingsSuspiciouslyMissing(result.order()) && attempt < MAX_RETRIES) {
                    suspicious = result;
                    log.warn("holdings=0인데 SELL 주문 존재 (시도 {}/{}) — Gemini 재요청", attempt, MAX_RETRIES);
                    continue;
                }
                return accept(result);
            } catch (GeminiJsonParseException e) {
                // 응답 JSON 형식 오류는 요청마다 달라질 수 있어 남은 시도 내에서 즉시 재요청
                lastFailure = e;
                log.warn("Gemini JSON 파싱 실패 (시도 {}/{}) — {}", attempt, MAX_RETRIES, e.getMessage());
            } catch (HttpServerErrorException e) {
                // GeminiClient는 503만 그대로 전파 — 재시도 대상
                lastFailure = e;
                log.warn("Gemini API 503 오류 (시도 {}/{}), {}초 후 재시도", attempt, MAX_RETRIES, retryDelayMs / 1000);
                if (attempt < MAX_RETRIES) {
                    sleepQuietly(retryDelayMs);
                }
            }
        }

        // 앞서 확보한 결과가 있으면 이후 실패 유형(503·파싱)과 무관하게 우선 반환
        if (suspicious != null) {
            log.warn("재요청이 모두 실패해 holdings 누락 의심 결과를 사용 — 마지막 실패: {}", lastFailure.getMessage());
            return accept(suspicious);
        }
        notifyPort.notifyGeminiError(lastFailure);
        if (lastFailure instanceof GeminiJsonParseException parseFailure) {
            throw parseFailure;
        }
        log.error("Gemini API 503 오류 {}회 재시도 후 최종 실패", MAX_RETRIES, lastFailure);
        throw new OcrException("Gemini API 503 오류 " + MAX_RETRIES + "회 재시도 후 실패", lastFailure);
    }

    // 최종 채택한 결과의 경고만 알림 — 재요청으로 버린 시도의 경고는 로그로만 남는다
    private ParsedOrder accept(OcrResultReconciler.Result result) {
        result.warnings().forEach(notifyPort::notifyOcrWarning);
        return result.order();
    }

    private boolean isHoldingsSuspiciouslyMissing(ParsedOrder order) {
        return order.holdings() == 0 && !order.sellOrders().isEmpty();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
