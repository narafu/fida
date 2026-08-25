package com.fida.adapter.out.sheets;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.SheetPort;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class GoogleSheetsAdapter implements SheetPort {

    private static final int MAX_RETRIES = 3;
    // 테스트에서 ReflectionTestUtils로 0으로 설정 가능
    long retryDelayMs = 2_000L;

    private final Sheets sheetsService;
    @Value("${google.sheets.spreadsheet-id}")
    private String spreadsheetId;
    @Value("${google.sheets.sheet-name}")
    private String sheetName;

    @Override
    public void update(TradingRecord record) {
        List<ValueRange> data = buildRangeData(record);
        BatchUpdateValuesRequest body = new BatchUpdateValuesRequest()
                .setValueInputOption("USER_ENTERED")
                .setData(data);

        // 고정 셀 범위를 매번 통째로 덮어쓰는 요청이라 재시도해도 멱등 — 5xx/429만 재시도, 그 외는 즉시 전파
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                sheetsService.spreadsheets().values()
                        .batchUpdate(spreadsheetId, body)
                        .execute();
                return;
            } catch (IOException e) {
                if (!isRetryable(e)) {
                    throw new SheetException("Google Sheets 업데이트 실패", e);
                }
                lastException = e;
                log.warn("Google Sheets 업데이트 재시도 (시도 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES && !sleepQuietly(retryDelayMs)) {
                    // 재시도 대기 중 인터럽트 — 종료 신호이므로 재시도 없이 즉시 전파
                    throw new SheetException("Google Sheets 업데이트 실패", e);
                }
            }
        }
        throw new SheetException("Google Sheets 업데이트 실패", lastException);
    }

    private boolean isRetryable(IOException e) {
        if (e instanceof GoogleJsonResponseException gje) {
            int status = gje.getStatusCode();
            return status == 429 || status >= 500;
        }
        return false;
    }

    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    List<ValueRange> buildRangeData(TradingRecord record) {
        List<OrderItem> buy = padTo3(record.order().buyOrders());
        List<OrderItem> sell = padTo3(record.order().sellOrders());

        List<ValueRange> data = new ArrayList<>(16);
        data.add(cell("A1", record.date().toString()));
        for (int i = 0; i < 3; i++) {
            int row = i + 2;
            data.add(cell("C" + row, fmt(buy.get(i).price())));
            data.add(cell("D" + row, fmtQty(buy.get(i).qty())));
        }
        for (int i = 0; i < 3; i++) {
            int row = i + 5;
            data.add(cell("C" + row, fmt(sell.get(i).price())));
            data.add(cell("D" + row, fmtQty(sell.get(i).qty())));
        }
        data.add(cell("A8", fmt(record.order().currentCycleStart())));
        data.add(cell("C8", fmt(record.order().avgPrice())));
        data.add(cell("D8", String.valueOf(record.order().holdings())));
        return data;
    }

    private ValueRange cell(String cellRef, String value) {
        return new ValueRange()
                .setRange(sheetName + "!" + cellRef)
                .setValues(List.of(List.of(value)));
    }

    private List<OrderItem> padTo3(List<OrderItem> items) {
        List<OrderItem> padded = new ArrayList<>(items);
        while (padded.size() < 3) {
            padded.add(new OrderItem(null, null));
        }
        return padded;
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.toPlainString() : "";
    }

    private String fmtQty(String qty) {
        return qty != null ? qty : "";
    }
}
