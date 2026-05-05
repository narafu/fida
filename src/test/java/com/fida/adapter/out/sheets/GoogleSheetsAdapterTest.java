package com.fida.adapter.out.sheets;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.model.ScrapedPost;
import com.fida.domain.model.TradingRecord;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GoogleSheetsAdapter 셀 매핑 테스트")
class GoogleSheetsAdapterTest {

    private static final String SHEET_NAME = "Fanding";

    private GoogleSheetsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GoogleSheetsAdapter(null);
        ReflectionTestUtils.setField(adapter, "spreadsheetId", "test-id");
        ReflectionTestUtils.setField(adapter, "sheetName", SHEET_NAME);
    }

    private TradingRecord recordWith(ParsedOrder order) {
        ScrapedPost post = new ScrapedPost("매매표", LocalDate.of(2024, 1, 15), "https://fanding.kr/1", List.of());
        return TradingRecord.of(post, order);
    }

    @Test
    @DisplayName("날짜는 A1 셀에 매핑된다")
    void date_maps_to_A1() {
        TradingRecord record = recordWith(new ParsedOrder(List.of(), List.of(), null, null, 0));
        List<ValueRange> ranges = adapter.buildRangeData(record);

        ValueRange a1 = findRange(ranges, SHEET_NAME + "!A1");
        assertThat(a1).isNotNull();
        assertThat(a1.getValues().get(0).get(0)).isEqualTo("2024-01-15");
    }

    @Test
    @DisplayName("매수 3건이 C2:D4에 행별로 매핑된다")
    void buy_orders_map_to_C2_D4() {
        List<OrderItem> buy = List.of(
                new OrderItem(new BigDecimal("75000"), "100"),
                new OrderItem(new BigDecimal("74000"), "50"),
                new OrderItem(null, null)
        );
        TradingRecord record = recordWith(new ParsedOrder(buy, List.of(), null, null, 150));

        List<ValueRange> ranges = adapter.buildRangeData(record);

        assertCellValue(ranges, SHEET_NAME + "!C2", "75000");
        assertCellValue(ranges, SHEET_NAME + "!D2", "100");
        assertCellValue(ranges, SHEET_NAME + "!C3", "74000");
        assertCellValue(ranges, SHEET_NAME + "!D3", "50");
        assertCellValue(ranges, SHEET_NAME + "!C4", "");
        assertCellValue(ranges, SHEET_NAME + "!D4", "");
    }

    @Test
    @DisplayName("매도 수량 ALL은 그대로 유지된다")
    void sell_qty_ALL_preserved() {
        List<OrderItem> sell = List.of(new OrderItem(new BigDecimal("80000"), "ALL"));
        TradingRecord record = recordWith(new ParsedOrder(List.of(), sell, null, null, 0));

        List<ValueRange> ranges = adapter.buildRangeData(record);

        assertCellValue(ranges, SHEET_NAME + "!D5", "ALL");
    }

    @Test
    @DisplayName("잔금/평단/보유개수는 A8, C8, D8에 매핑된다")
    void summary_maps_to_A8_C8_D8() {
        ParsedOrder order = new ParsedOrder(
                List.of(), List.of(),
                new BigDecimal("1000000"), new BigDecimal("72000"), 200
        );
        TradingRecord record = recordWith(order);

        List<ValueRange> ranges = adapter.buildRangeData(record);

        assertCellValue(ranges, SHEET_NAME + "!A8", "1000000");
        assertCellValue(ranges, SHEET_NAME + "!C8", "72000");
        assertCellValue(ranges, SHEET_NAME + "!D8", "200");
    }

    @Test
    @DisplayName("null 값은 빈 문자열로 변환된다")
    void null_values_become_empty_string() {
        TradingRecord record = recordWith(new ParsedOrder(List.of(), List.of(), null, null, 0));

        List<ValueRange> ranges = adapter.buildRangeData(record);

        assertCellValue(ranges, SHEET_NAME + "!A8", "");
        assertCellValue(ranges, SHEET_NAME + "!C8", "");
    }

    @Test
    @DisplayName("buildRangeData는 정확히 16개 범위를 반환한다")
    void buildRangeData_returns_16_ranges() {
        TradingRecord record = recordWith(new ParsedOrder(List.of(), List.of(), null, null, 0));
        List<ValueRange> ranges = adapter.buildRangeData(record);
        assertThat(ranges).hasSize(16);
    }

    private ValueRange findRange(List<ValueRange> ranges, String rangeName) {
        return ranges.stream().filter(r -> rangeName.equals(r.getRange())).findFirst().orElse(null);
    }

    private void assertCellValue(List<ValueRange> ranges, String rangeName, String expected) {
        ValueRange vr = findRange(ranges, rangeName);
        assertThat(vr).as("range %s not found", rangeName).isNotNull();
        assertThat(vr.getValues().get(0).get(0)).isEqualTo(expected);
    }
}
