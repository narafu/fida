package com.fida.application.service;

import com.fida.domain.model.KistaResult;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.in.ProcessImagesUseCase;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.KistaPort;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import com.fida.domain.port.out.ScraperPort;
import com.fida.domain.port.out.SheetPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
public class TradingRecordService implements ProcessTradingRecordUseCase, ProcessImagesUseCase {

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
        processPost(scraper.scrape());
    }

    @Override
    public KistaResult process(byte[] image, LocalDate tradeDate) {
        // 이미지 직접 제공 시 OCR → KISTA 전송만 수행 (sheet/notify 생략), KISTA 실패는 예외 전파
        var post = new ScrapedPost("수동 분석", tradeDate, "-", List.of(image));
        var order = ocr.analyze(post.images());
        var record = TradingRecord.of(post, order);
        var result = kista.sendOrders(record);
        safeNotify(() -> notify.notifyKistaSuccess(record, result.id()));
        return result;
    }

    // OCR → Sheet → Telegram → Kista 공통 파이프라인
    private void processPost(ScrapedPost post) {
        var order = ocr.analyze(post.images());

        var record = TradingRecord.of(post, order);
        sheet.update(record);
        notify.notify(record);

        // KISTA 실패는 sheet/notify에 영향 없음 — 실패 알림만 전송
        try {
            var result = kista.sendOrders(record);
            safeNotify(() -> notify.notifyKistaSuccess(record, result.id()));
        } catch (Exception e) {
            log.warn("KISTA 전송 실패 (무시): {}", e.getMessage());
            safeNotify(() -> notify.notifyKistaFailure(record, e));
        }
    }

    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("KISTA 결과 알림 실패 (무시): {}", e.getMessage());
        }
    }
}
