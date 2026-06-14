package com.fida.application.service;

import com.fida.domain.model.KistaResult;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import com.fida.domain.port.out.ScraperPort;
import com.fida.domain.port.out.SheetPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingRecordService 테스트")
class TradingRecordServiceTest {

    @Mock ScraperPort scraperPort;
    @Mock OcrPort ocrPort;
    @Mock SheetPort sheetPort;
    @Mock NotifyPort notifyPort;
    @Mock KistaPort kistaPort;

    private final ScrapedPost samplePost = new ScrapedPost(
            "매매표", LocalDate.of(2024, 1, 15), "https://fanding.kr/1", List.of(new byte[]{1}));
    private final ParsedOrder sampleOrder = new ParsedOrder(
            List.of(), List.of(), null, null, null, 0);
    private final KistaResult sampleKistaResult = new KistaResult(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            LocalDate.of(2024, 1, 15), "SOXL", null, null, null, 0, List.of());

    @Test
    @DisplayName("process()는 scrape → OCR → sheet 업데이트 → notify 순으로 실행된다")
    void process_executes_full_pipeline_in_order() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(samplePost.images())).thenReturn(sampleOrder);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.empty());

        service.process();

        var inOrder = inOrder(scraperPort, ocrPort, sheetPort, notifyPort);
        inOrder.verify(scraperPort).scrape();
        inOrder.verify(ocrPort).analyze(samplePost.images());
        inOrder.verify(sheetPort).update(any(TradingRecord.class));
        inOrder.verify(notifyPort).notify(any(TradingRecord.class));
    }

    @Test
    @DisplayName("sheet와 notify에 전달되는 TradingRecord는 ScrapedPost 정보를 담는다")
    void process_passes_correct_tradingRecord_to_sheet_and_notify() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.empty());

        service.process();

        var captor = ArgumentCaptor.forClass(TradingRecord.class);
        verify(sheetPort).update(captor.capture());
        assertThat(captor.getValue().postTitle()).isEqualTo("매매표");
        assertThat(captor.getValue().date()).isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    @DisplayName("KistaPort가 주입되면 sendOrders를 호출한다")
    void process_calls_kista_when_present() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process();

        verify(kistaPort).sendOrders(any(TradingRecord.class));
    }

    @Test
    @DisplayName("KistaPort가 없으면 KISTA 호출을 건너뛴다")
    void process_skips_kista_when_absent() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.empty());

        service.process();

        verifyNoInteractions(kistaPort);
    }

    @Test
    @DisplayName("KistaPort 예외 발생 시 서비스는 정상 완료한다")
    void process_continues_when_kista_throws() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        doThrow(new RuntimeException("KISTA 연결 오류")).when(kistaPort).sendOrders(any());
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process(); // 예외 전파 없이 완료

        verify(sheetPort).update(any());
        verify(notifyPort).notify(any());
    }

    @Test
    @DisplayName("KISTA 전송 성공 시 notifyKistaSuccess를 호출한다")
    void process_notifies_kista_success_when_sendOrders_succeeds() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        when(kistaPort.sendOrders(any())).thenReturn(sampleKistaResult);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process();

        verify(notifyPort).notifyKistaSuccess(any(TradingRecord.class), any());
    }

    @Test
    @DisplayName("KISTA 전송 실패 시 notifyKistaFailure를 호출하고 서비스는 정상 완료한다")
    void process_notifies_kista_failure_when_sendOrders_throws() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        var cause = new RuntimeException("KISTA 연결 오류");
        doThrow(cause).when(kistaPort).sendOrders(any());
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process();

        verify(notifyPort).notifyKistaFailure(any(TradingRecord.class), eq(cause));
        verify(sheetPort).update(any());
        verify(notifyPort).notify(any());
    }

    @Test
    @DisplayName("KISTA 결과 알림 자체가 실패해도 서비스는 정상 완료한다")
    void process_continues_when_kista_notification_throws() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        when(kistaPort.sendOrders(any())).thenReturn(sampleKistaResult);
        doThrow(new RuntimeException("알림 실패")).when(notifyPort).notifyKistaSuccess(any(), any());
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process(); // 알림 실패해도 예외 전파 없이 완료

        verify(sheetPort).update(any());
        verify(notifyPort).notify(any());
    }

    @Test
    @DisplayName("sheet.update는 KISTA 결과에 관계없이 항상 먼저 완료된다")
    void process_sheet_update_always_precedes_kista() {
        when(scraperPort.scrape()).thenReturn(samplePost);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        doThrow(new RuntimeException("KISTA 오류")).when(kistaPort).sendOrders(any());
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process();

        var inOrder = inOrder(sheetPort, kistaPort);
        inOrder.verify(sheetPort).update(any());
        inOrder.verify(kistaPort).sendOrders(any());
    }

    @Test
    @DisplayName("process(images, date)는 scraper/sheet/notify를 호출하지 않고 KISTA만 전송한다")
    void processImages_only_calls_kista_skipping_sheet_and_notify() {
        var image = new byte[]{1, 2, 3};
        var date = LocalDate.of(2026, 6, 13);
        when(ocrPort.analyze(anyList())).thenReturn(sampleOrder);
        when(kistaPort.sendOrders(any())).thenReturn(sampleKistaResult);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process(image, date);

        verifyNoInteractions(scraperPort);
        verifyNoInteractions(sheetPort);
        verify(ocrPort).analyze(anyList());
        verify(kistaPort).sendOrders(any(TradingRecord.class));
    }

    @Test
    @DisplayName("process(images, date)는 KISTA 없을 때 IllegalStateException을 던진다")
    void processImages_throws_when_kista_absent() {
        var image = new byte[]{1, 2, 3};
        var date = LocalDate.of(2026, 6, 13);
        when(ocrPort.analyze(anyList())).thenReturn(sampleOrder);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.process(image, date));
    }

    @Test
    @DisplayName("process(images, date)가 KISTA에 전달하는 TradingRecord는 제공된 날짜와 수동 분석 제목을 가진다")
    void processImages_tradingRecord_has_provided_date_and_manual_title() {
        var date = LocalDate.of(2026, 6, 13);
        when(ocrPort.analyze(any())).thenReturn(sampleOrder);
        when(kistaPort.sendOrders(any())).thenReturn(sampleKistaResult);
        var service = new TradingRecordService(scraperPort, ocrPort, sheetPort, notifyPort, Optional.of(kistaPort));

        service.process(new byte[]{1}, date);

        var captor = ArgumentCaptor.forClass(TradingRecord.class);
        verify(kistaPort).sendOrders(captor.capture());
        assertThat(captor.getValue().date()).isEqualTo(date);
        assertThat(captor.getValue().postTitle()).isEqualTo("수동 분석");
    }

}
