package com.fida.application.service;

import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.KistaPort;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import com.fida.domain.port.out.ScraperPort;
import com.fida.domain.port.out.SheetPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class TradingRecordService implements ProcessTradingRecordUseCase {

    private final ScraperPort scraper;
    private final OcrPort ocr;
    private final SheetPort sheet;
    private final NotifyPort notify;
    private final KistaPort kista;

    public TradingRecordService(ScraperPort scraper, OcrPort ocr, @Lazy SheetPort sheet,
                                NotifyPort notify, KistaPort kista) {
        this.scraper = scraper;
        this.ocr = ocr;
        this.sheet = sheet;
        this.notify = notify;
        this.kista = kista;
    }

    @Override
    public void process() {
        long startedAt = System.currentTimeMillis();
        processPost(scraper.scrape());
        // 서버 로그만으로 실행 결과를 확인할 수 있도록 전체 완료·소요 시간 기록
        log.info("파이프라인 완료 ({}ms)", System.currentTimeMillis() - startedAt);
    }

    // OCR → Sheet → Telegram → Kista 공통 파이프라인
    private void processPost(ScrapedPost post) {
        var order = ocr.analyze(post.images());
        var record = TradingRecord.of(post, order);

        // 시트·매매 알림 실패가 KISTA 주문 전송을 막지 않도록 실패를 모았다가 KISTA 이후 전파
        var failures = new ArrayList<RuntimeException>();
        runIsolated("시트 기록 date=" + record.date(), () -> sheet.update(record), failures);
        runIsolated("매매 알림", () -> notify.notify(record), failures);
        sendToKista(record);

        // 첫 실패를 대표로 던지고 나머지는 suppressed로 보존 — 스케줄러가 실패 알림·재시도를 처리
        if (!failures.isEmpty()) {
            var first = failures.getFirst();
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private void runIsolated(String stepName, Runnable step, List<RuntimeException> failures) {
        try {
            step.run();
            log.info("파이프라인 단계 완료: {}", stepName);
        } catch (RuntimeException e) {
            log.error("파이프라인 단계 실패: {} (KISTA 전송은 계속 진행): {}", stepName, e.getMessage(), e);
            failures.add(e);
        }
    }

    // KISTA 실패는 파이프라인 실패로 보지 않음 — 별도 실패 알림만 전송
    private void sendToKista(TradingRecord record) {
        try {
            var id = kista.sendOrders(record);
            log.info("KISTA 전송 완료 id={} tradeDate={}", id, record.date());
            notify.notifyKistaSuccess(record, id);
        } catch (Exception e) {
            log.warn("KISTA 전송 실패 (무시): {}", e.getMessage());
            notify.notifyKistaFailure(record, e);
        }
    }
}
