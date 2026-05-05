package com.fida.application.service;

import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.KistaPort;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import com.fida.domain.port.out.ScraperPort;
import com.fida.domain.port.out.SheetPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TradingRecordService implements ProcessTradingRecordUseCase {

    private static final Logger log = LoggerFactory.getLogger(TradingRecordService.class);

    private final ScraperPort scraper;
    private final OcrPort ocr;
    private final SheetPort sheet;
    private final NotifyPort notify;
    private final Optional<KistaPort> kista;

    public TradingRecordService(ScraperPort scraper, OcrPort ocr, @Lazy SheetPort sheet,
                                NotifyPort notify, Optional<KistaPort> kista) {
        this.scraper = scraper;
        this.ocr = ocr;
        this.sheet = sheet;
        this.notify = notify;
        this.kista = kista;
    }

    @Override
    public void process() {
        var post = scraper.scrape();
        var order = ocr.analyze(post.images());
        var record = TradingRecord.of(post, order);
        sheet.update(record);
        notify.notify(record);
//        kista.ifPresent(k -> {
//            try {
//                k.sendOrders(record);
//            } catch (Exception e) {
//                log.warn("KISTA 전송 실패 (무시): {}", e.getMessage());
//            }
//        });
    }
}
