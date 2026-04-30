package com.fida.adapter.out.sheets;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.SheetPort;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@Lazy
public class GoogleSheetsAdapter implements SheetPort {

    private final Sheets sheetsService;
    private final String spreadsheetId;
    private final String sheetName;

    public GoogleSheetsAdapter(
            Sheets sheetsService,
            @Value("${google.sheets.spreadsheet-id}") String spreadsheetId,
            @Value("${google.sheets.sheet-name}") String sheetName) {
        this.sheetsService = sheetsService;
        this.spreadsheetId = spreadsheetId;
        this.sheetName = sheetName;
    }

    @Override
    public void update(TradingRecord record) {
        List<ValueRange> data = buildRangeData(record);
        BatchUpdateValuesRequest body = new BatchUpdateValuesRequest()
                .setValueInputOption("USER_ENTERED")
                .setData(data);
        try {
            sheetsService.spreadsheets().values()
                    .batchUpdate(spreadsheetId, body)
                    .execute();
        } catch (IOException e) {
            throw new SheetException("Google Sheets 업데이트 실패", e);
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
