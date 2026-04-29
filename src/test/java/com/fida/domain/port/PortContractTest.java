package com.fida.domain.port;

import com.fida.domain.model.ParsedOrder;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.KistaPort;
import com.fida.domain.port.out.NotifyPort;
import com.fida.domain.port.out.OcrPort;
import com.fida.domain.port.out.ScraperPort;
import com.fida.domain.port.out.SheetPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Port 인터페이스 계약 테스트")
class PortContractTest {

    @Test
    @DisplayName("ProcessTradingRecordUseCase는 process() 메서드를 가진다")
    void processTradingRecordUseCase_has_process_method() throws NoSuchMethodException {
        var method = ProcessTradingRecordUseCase.class.getMethod("process");
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("ScraperPort는 scrape()로 ScrapedPost를 반환한다")
    void scraperPort_returns_scrapedPost() throws NoSuchMethodException {
        var method = ScraperPort.class.getMethod("scrape");
        assertThat(method.getReturnType()).isEqualTo(ScrapedPost.class);
    }

    @Test
    @DisplayName("OcrPort는 images를 받아 ParsedOrder를 반환한다")
    void ocrPort_takes_images_and_returns_parsedOrder() throws NoSuchMethodException {
        var method = OcrPort.class.getMethod("analyze", List.class);
        assertThat(method.getReturnType()).isEqualTo(ParsedOrder.class);
    }

    @Test
    @DisplayName("SheetPort는 TradingRecord를 받아 업데이트한다")
    void sheetPort_accepts_tradingRecord() throws NoSuchMethodException {
        var method = SheetPort.class.getMethod("update", TradingRecord.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("NotifyPort는 TradingRecord를 받아 알림을 전송한다")
    void notifyPort_accepts_tradingRecord() throws NoSuchMethodException {
        var method = NotifyPort.class.getMethod("notify", TradingRecord.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("KistaPort는 TradingRecord를 받아 주문을 전송한다")
    void kistaPort_accepts_tradingRecord() throws NoSuchMethodException {
        var method = KistaPort.class.getMethod("sendOrders", TradingRecord.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("모든 Port 인터페이스는 domain 패키지에 위치한다")
    void all_ports_reside_in_domain_package() {
        assertThat(ProcessTradingRecordUseCase.class.getPackageName())
                .startsWith("com.fida.domain.port");
        assertThat(ScraperPort.class.getPackageName())
                .startsWith("com.fida.domain.port");
        assertThat(OcrPort.class.getPackageName())
                .startsWith("com.fida.domain.port");
        assertThat(SheetPort.class.getPackageName())
                .startsWith("com.fida.domain.port");
        assertThat(NotifyPort.class.getPackageName())
                .startsWith("com.fida.domain.port");
        assertThat(KistaPort.class.getPackageName())
                .startsWith("com.fida.domain.port");
    }
}
