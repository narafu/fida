package com.fida.adapter.out.ocr;

import com.fida.adapter.out.ocr.GeminiResponseParser.GeminiOrderResult;
import com.fida.adapter.out.ocr.GeminiResponseParser.LabeledValue;
import com.fida.adapter.out.ocr.GeminiResponseParser.RawOrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OcrResultReconciler 테스트")
class OcrResultReconcilerTest {

    @Test
    @DisplayName("보정 경고를 알림 없이 결과에 담아 반환한다")
    void reconcile_collects_warnings_without_side_effects() {
        var raw = new GeminiOrderResult(
                List.of(), List.of(), List.of(new RawOrderItem(new BigDecimal("236.54"), "ALL")),
                null, null,
                List.of(new LabeledValue("현사이클 시작", new BigDecimal("11783.18"))),
                List.of(new LabeledValue("현사이클 실현수익", new BigDecimal("12.34"))),
                new BigDecimal("99.99"), new BigDecimal("50"), new BigDecimal("10"), null);

        var result = OcrResultReconciler.reconcile(raw);

        assertThat(result.order().sellOrders()).hasSize(1);
        assertThat(result.order().currentCycleStart()).isEqualByComparingTo("11783.18");
        assertThat(result.order().currentCycleRealizedPnl()).isEqualByComparingTo("12.34");
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("sell_rows"))
                .anyMatch(w -> w.contains("실현수익 불일치"));
    }
}
