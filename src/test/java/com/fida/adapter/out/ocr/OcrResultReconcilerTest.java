package com.fida.adapter.out.ocr;

import com.fida.adapter.out.ocr.GeminiResponseParser.GeminiOrderResult;
import com.fida.adapter.out.ocr.GeminiResponseParser.LabeledValue;
import com.fida.adapter.out.ocr.GeminiResponseParser.OrderTable;
import com.fida.adapter.out.ocr.GeminiResponseParser.RawOrderItem;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OcrResultReconciler 테스트")
class OcrResultReconcilerTest {

    // 픽스처(Gemini 응답 본문) → 파서 → 보정 결과
    private static OcrResultReconciler.Result reconcile(String fixture) {
        return OcrResultReconciler.reconcile(GeminiResponseParser.parse(OcrFixtures.load(fixture)));
    }

    @Test
    @DisplayName("보정 경고를 알림 없이 결과에 담아 반환한다")
    void reconcile_collects_warnings_without_side_effects() {
        var raw = new GeminiOrderResult(
                List.of(new OrderTable("매수가", List.of()),
                        new OrderTable("매도가", List.of(new RawOrderItem(new BigDecimal("236.54"), "ALL")))),
                null, null,
                List.of(new LabeledValue("현사이클 시작", new BigDecimal("11783.18"))),
                List.of(new LabeledValue("현사이클 실현수익", new BigDecimal("12.34"))),
                new BigDecimal("99.99"), new BigDecimal("50"), new BigDecimal("10"), null);

        var result = OcrResultReconciler.reconcile(raw);

        assertThat(result.order().sellOrders()).hasSize(1);
        assertThat(result.order().currentCycleStart()).isEqualByComparingTo("11783.18");
        assertThat(result.order().currentCycleRealizedPnl()).isEqualByComparingTo("12.34");
        assertThat(result.orderTableError()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("실현수익 불일치"));
    }

    @Test
    @DisplayName("Gemini 응답을 ParsedOrder로 정상 파싱한다")
    void analyze_parses_gemini_response_to_parsedOrder() {
        var reconciled = reconcile("basic-response");
        ParsedOrder result = reconciled.order();

        assertThat(result.buyOrders()).hasSize(1);
        assertThat(result.buyOrders().get(0).price()).isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(result.buyOrders().get(0).qty()).isEqualTo("100");
        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.sellOrders().get(0).qty()).isEqualTo("ALL");
        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(result.avgPrice()).isEqualByComparingTo(new BigDecimal("72000"));
        assertThat(result.holdings()).isEqualTo(200);
    }

    @Test
    @DisplayName("holdings가 음수이면 0으로 보정한다")
    void analyze_corrects_negative_holdings_to_zero() {
        var reconciled = reconcile("negative-holdings");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isZero();
        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("holdings가 0이면 avgPrice를 null로 강제한다")
    void analyze_nullifies_avgPrice_when_holdings_zero() {
        var reconciled = reconcile("holdings-zero-with-avg-price");
        ParsedOrder result = reconciled.order();

        assertThat(result.avgPrice()).isNull();
    }

    @Test
    @DisplayName("sell 앞 행이 모두 null이고 마지막 행에만 값이 있어도 sell을 파싱한다")
    void analyze_parses_sell_when_only_last_row_has_data() {
        // 오늘 실제 발생 케이스: Gemini가 sell=[] 반환 → 이 테스트는 Java 필터 로직 검증
        var reconciled = reconcile("sell-only-last-row");
        ParsedOrder result = reconciled.order();

        assertThat(result.buyOrders()).hasSize(2);
        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.sellOrders().get(0).price()).isEqualByComparingTo(new BigDecimal("236.54"));
        assertThat(result.sellOrders().get(0).qty()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("2026-11-26 회귀: 매수가 표가 비고 매도가 표에만 3행이 있으면 모두 sell로 분류한다")
    void reconcile_classifies_rows_by_header_not_position() {
        var reconciled = reconcile("order-tables-sell-only-regression");

        assertThat(reconciled.orderTableError()).isNull();
        assertThat(reconciled.order().buyOrders()).isEmpty();
        assertThat(reconciled.order().sellOrders()).containsExactly(
                new OrderItem(new BigDecimal("57.79"), "8"),
                new OrderItem(new BigDecimal("73.07"), "11"),
                new OrderItem(new BigDecimal("33.54"), "4"));
    }

    @Test
    @DisplayName("rows의 null 원소는 빈 행으로 건너뛴다")
    void reconcile_skips_null_rows() {
        var reconciled = OcrResultReconciler.reconcile(GeminiResponseParser.parse(
                "{\"order_tables\":[{\"header\":\"매수가\",\"rows\":[null,null]},"
                        + "{\"header\":\"매도가\",\"rows\":[null,{\"price\":57.79,\"qty\":8}]}],\"holding_qty\":8}"));

        assertThat(reconciled.orderTableError()).isNull();
        assertThat(reconciled.order().buyOrders()).isEmpty();
        assertThat(reconciled.order().sellOrders()).containsExactly(new OrderItem(new BigDecimal("57.79"), "8"));
    }

    @Test
    @DisplayName("매수가 표가 2개이고 매도가 표가 없으면 구조 오류를 반환한다")
    void reconcile_reports_error_when_order_tables_structure_invalid() {
        var reconciled = reconcile("order-tables-duplicate-buy");

        assertThat(reconciled.orderTableError()).isNotNull().contains("매수가");
        assertThat(reconciled.order().buyOrders()).isEmpty();
        assertThat(reconciled.order().sellOrders()).isEmpty();
    }

    @Test
    @DisplayName("order_tables가 없으면 구조 오류를 반환한다")
    void reconcile_reports_error_when_order_tables_missing() {
        var reconciled = OcrResultReconciler.reconcile(
                GeminiResponseParser.parse("{\"holding_qty\":0}"));

        assertThat(reconciled.orderTableError()).isNotNull();
    }

    @Test
    @DisplayName("holdings가 0이어도 cumulative_qty가 있으면 이를 우선 사용한다")
    void analyze_prefers_cumulative_qty_when_holdings_is_zero() {
        var reconciled = reconcile("cumulative-over-zero-holdings");
        ParsedOrder result = reconciled.order();

        assertThat(result.sellOrders()).hasSize(1);
        assertThat(result.holdings()).isEqualTo(27);
        assertThat(result.avgPrice()).isEqualByComparingTo(new BigDecimal("35.564"));
    }

    @Test
    @DisplayName("holding_qty와 cumulative_qty가 다르면 누적개수를 우선하고 경고한다")
    void analyze_prefers_cumulative_qty_and_warns_when_holding_candidates_disagree() {
        // 운영 사례: 매수개수 57을 holding_qty로 오인식했지만 누적개수는 74로 정확히 파싱됨
        var reconciled = reconcile("holding-cumulative-disagree");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isEqualTo(74);
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("holding_qty=57, cumulative_qty=74")).hasSize(1);
    }

    @Test
    @DisplayName("cumulative_qty가 holding_qty와 10배 이상 벌어지면 환각으로 보고 holding_qty를 사용한다")
    void analyze_prefers_holding_qty_when_cumulative_qty_is_magnitude_mismatch() {
        // 운영 사례(2026-08-21): "누적개수" 표가 없는 이미지에서 누적실현수익(4,913.84)을
        // cumulative_qty=4913으로 환각 응답, 실제 보유수량은 holding_qty=49
        var reconciled = reconcile("cumulative-magnitude-mismatch");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isEqualTo(49);
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("holding_qty=49, cumulative_qty=4913")).hasSize(1);
    }

    @Test
    @DisplayName("holding_qty가 명시적으로 0이어도 cumulative_qty와 배율 이상 벌어지면 환각으로 보고 0을 사용한다")
    void analyze_prefers_explicit_zero_holding_qty_when_cumulative_qty_is_magnitude_mismatch() {
        // 운영 사례(2026-09-21): "현재 보유 개수"가 명시적으로 0인 이미지에서 누적실현수익(4,299.87)을
        // cumulative_qty=4299로 환각 응답. 기존 코드는 holding_qty가 0(비양수)이면 비교 자체를 건너뛰어
        // 환각값 4299를 그대로 보유수량으로 사용하는 결함이 있었음
        var reconciled = reconcile("explicit-zero-holding-magnitude-mismatch");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isEqualTo(0);
        assertThat(result.avgPrice()).isNull();
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("holding_qty=0, cumulative_qty=4299")).hasSize(1);
    }

    @Test
    @DisplayName("cumulative_qty에 소수 금액이 오면 수량 후보에서 제외하고 holding_qty를 사용한다")
    void analyze_rejects_decimal_cumulative_qty_as_misread_amount() {
        // 운영 사례(2026-09-28): 누적실현수익 $4,347.11이 cumulative_qty로 들어옴 — Integer로 받으면 4347로 조용히 잘렸음
        var reconciled = reconcile("decimal-cumulative-qty");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isEqualTo(13);
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("cumulative_qty=4347.11")).hasSize(1);
    }

    @Test
    @DisplayName("holding_qty·cumulative_qty가 모두 없으면 holdings 필드 값을 쓰지 않고 0으로 본다")
    void analyze_ignores_unguarded_holdings_field() {
        // holdings 필드는 프롬프트에서 제거됨 — 과거 응답처럼 환각값(4913)이 실려와도 배율 검증 없는 경로로 채택하지 않음
        var reconciled = reconcile("unguarded-holdings-field");
        ParsedOrder result = reconciled.order();

        assertThat(result.holdings()).isZero();
    }

    @Test
    @DisplayName("current_cycle_start가 null이면 자금 표의 현사이클 시작 행으로 보정한다")
    void analyze_falls_back_to_capital_row_when_current_cycle_start_is_null() {
        // 운영 사례: Gemini가 오른쪽 상단 자금 표는 읽었지만 current_cycle_start 필드만 null로 반환
        var reconciled = reconcile("capital-row-fallback");
        ParsedOrder result = reconciled.order();

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("11783.18"));
    }

    @Test
    @DisplayName("current_cycle_start가 잔금+보유평가액 대비 비정상적으로 크면 경고 로그를 남긴다")
    void analyze_warns_when_current_cycle_start_implausible_vs_cash_and_holdings_value() {
        // 운영 사례(2026-07-30): Gemini가 이미지 숫자를 오판독해 현사이클 시작을 143467.67로 반환
        // (잔금 2934.92 + 보유 91주 x 평단 127.458 = 약 14533.6로 실제 값과 10배 가까이 차이)
        var reconciled = reconcile("cycle-start-implausible");
        ParsedOrder result = reconciled.order();

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("143467.67"));
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("current_cycle_start 이상치 의심")).hasSize(1);
    }

    @Test
    @DisplayName("잔금 표를 못 읽었으면 시즌 시작원금 대비 비율로 current_cycle_start 이상치를 경고한다")
    void analyze_warns_using_season_start_capital_when_cash_balance_missing() {
        // 운영 사례(2026-08-14): 우측 자금 표 전체(잔금 포함)를 못 읽어 잔금 기준 검증이 무력화된 채
        // capital_rows에 잘못 섞인 값(710)이 current_cycle_start로 그대로 채택됨
        var reconciled = reconcile("cycle-start-cash-missing");
        ParsedOrder result = reconciled.order();

        assertThat(result.currentCycleStart()).isEqualByComparingTo(new BigDecimal("710"));
        assertThat(reconciled.warnings()).filteredOn(w -> w.contains("current_cycle_start 이상치 의심")).hasSize(1);
    }

    @Test
    @DisplayName("current_cycle_start가 잔금+보유평가액과 비슷하면 경고하지 않는다")
    void analyze_does_not_warn_when_current_cycle_start_plausible() {
        var reconciled = reconcile("cycle-start-plausible");
        assertThat(reconciled.warnings()).noneMatch(w -> w.contains("current_cycle_start 이상치 의심"));
    }
}
