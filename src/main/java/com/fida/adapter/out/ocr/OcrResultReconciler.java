package com.fida.adapter.out.ocr;

import com.fida.adapter.out.ocr.GeminiResponseParser.GeminiOrderResult;
import com.fida.adapter.out.ocr.GeminiResponseParser.LabeledValue;
import com.fida.adapter.out.ocr.GeminiResponseParser.RawOrderItem;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.ParsedOrder;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Gemini 원시 결과의 오판독 보정·이상치 감지 — 알림 없이 경고 문구만 수집하는 순수 로직
@Slf4j
final class OcrResultReconciler {

    // current_cycle_start가 (잔금+보유평가액) 대비 이 배율을 벗어나면 OCR 오판독으로 의심
    private static final BigDecimal CURRENT_CYCLE_START_RATIO_MAX = new BigDecimal("5");
    private static final BigDecimal CURRENT_CYCLE_START_RATIO_MIN = new BigDecimal("0.2");

    // holding_qty 대비 cumulative_qty가 이 배율 이상 벌어지면 Gemini 환각(다른 필드 오독)으로 의심
    private static final int HOLDINGS_MAGNITUDE_MISMATCH_RATIO = 10;

    private final GeminiOrderResult raw; // 보정 대상 원시 결과
    private final List<String> warnings = new ArrayList<>(); // 호출자가 알림으로 보낼 경고

    // 보정 결과 + 알림 대상 경고
    record Result(ParsedOrder order, List<String> warnings) {}

    private OcrResultReconciler(GeminiOrderResult raw) {
        this.raw = raw;
    }

    static Result reconcile(GeminiOrderResult raw) {
        return new OcrResultReconciler(raw).reconcile();
    }

    private Result reconcile() {
        int holdings = resolveHoldings();
        BigDecimal currentCycleStart = resolveCurrentCycleStart();
        BigDecimal avgPrice = (holdings == 0) ? null : raw.avgPrice();
        List<OrderItem> buyOrders = toOrderItems(raw.buy());
        List<OrderItem> sellOrders = resolveSellOrders();
        // sell이 비어있으면 Gemini가 매도 주문을 누락했을 가능성 — 로그로 원인 추적
        if (sellOrders.isEmpty()) {
            log.warn("Gemini sell 파싱 결과 비어있음 — 원시 sell 데이터: {}", raw.sell());
        }
        // holdings=0 & sell 존재 시 OCR 오파싱 가능성 — 원문은 GeminiResponseParser가 INFO로 남김
        if (holdings == 0 && !sellOrders.isEmpty()) {
            log.warn("holdings=0 인데 SELL 주문 존재 — 원시 결과: {}", raw);
        }
        // "현사이클 시작"과 "시즌 시작원금" 행을 혼동한 운영 사례 재발 감지
        if (currentCycleStart != null && raw.seasonStartCapital() != null
                && currentCycleStart.compareTo(raw.seasonStartCapital()) == 0) {
            warn("current_cycle_start가 season_start_capital과 동일함(" + currentCycleStart
                    + ") — \"현사이클 시작\"/\"시즌 시작원금\" 혼동 파싱 가능성, 시트·KISTA 값 확인 필요");
        }
        // 운영 사례(2026-07-30): 이미지 숫자 자체를 오판독해 현사이클 시작이 실제보다 10배 가까이 크게 나온 사례 재발 감지
        checkCurrentCycleStartPlausibility(currentCycleStart, holdings, avgPrice);

        ParsedOrder order = new ParsedOrder(buyOrders, sellOrders, currentCycleStart,
                resolveCurrentCycleRealizedPnl(), avgPrice, holdings);
        return new Result(order, List.copyOf(warnings));
    }

    private void warn(String warning) {
        log.warn(warning);
        warnings.add(warning);
    }

    private List<OrderItem> toOrderItems(List<RawOrderItem> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(i -> i.price() != null || i.qty() != null)
                .map(i -> new OrderItem(i.price(), i.qty() != null ? String.valueOf(i.qty()) : null))
                .toList();
    }

    private List<OrderItem> resolveSellOrders() {
        List<OrderItem> sellOrders = toOrderItems(raw.sell());
        List<OrderItem> sellRows = toOrderItems(raw.sellRows());

        // 운영 사례(2026-05-28): sell이 비어있지 않아도 일부 유효 행만 담고 다른 행을 누락시킬 수 있음 —
        // 물리적 3행을 보존하는 sell_rows의 유효 행 수가 sell보다 많으면(전체/부분 누락) sell_rows를 신뢰한다.
        if (sellRows.size() <= sellOrders.size()) {
            return sellOrders;
        }
        warn(sellOrders.isEmpty()
                ? "Gemini sell 누락을 sell_rows에서 복구: " + sellRows
                : "Gemini sell 부분 누락 감지(sell=" + sellOrders + ", sell_rows=" + sellRows + ") — sell_rows 전체를 사용");
        return sellRows;
    }

    private int resolveHoldings() {
        // 이미지의 명시적 누적개수를 우선해 매수개수를 holding_qty로 오인식한 결과를 차단한다.
        // 단, 운영 사례(2026-08-21): "누적개수" 표 자체가 없는 이미지에서 Gemini가 누적실현수익 등
        // 엉뚱한 숫자를 cumulative_qty로 환각 응답한 사례 발견 — 두 값이 10배 이상 벌어지면 신뢰할 수 없는
        // 값으로 보고 명시적 라벨 매칭인 holding_qty를 대신 사용한다.
        // 운영 사례(2026-09-21): "현재 보유 개수" 라벨이 명시적으로 0인 이미지에서도 같은 환각이 재현됨 —
        // holding_qty=0은 유효한 신호(라벨 미발견=null과 구분)이므로 isPositive 대신 null 체크로 비교 대상에 포함한다.
        Integer holdingQty = toQuantity(raw.holdingQty(), "holding_qty");
        Integer cumulativeQty = toQuantity(raw.cumulativeQty(), "cumulative_qty");
        if (isPositive(cumulativeQty)) {
            if (holdingQty != null && !cumulativeQty.equals(holdingQty)) {
                boolean implausible = isMagnitudeMismatch(cumulativeQty, holdingQty);
                warn("OCR 수량 불일치: holding_qty=" + holdingQty
                        + ", cumulative_qty=" + cumulativeQty
                        + (implausible
                                ? " — 배율 이상으로 cumulative_qty 환각 의심, holding_qty를 최종 보유수량으로 사용"
                                : " — 누적개수를 최종 보유수량으로 사용"));
                if (implausible) {
                    return holdingQty;
                }
            }
            return cumulativeQty;
        }
        return isPositive(holdingQty) ? holdingQty : 0;
    }

    // 운영 사례(2026-08-21·09-21·09-28): "누적실현수익 $4,347.11" 같은 금액이 수량 필드로 들어옴 —
    // 소수 부분이 있는 값은 개수일 수 없으므로 수량 후보에서 제외한다
    private Integer toQuantity(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            warn("OCR 수량 필드 " + field + "=" + value.toPlainString()
                    + " — 정수가 아닌 값(금액 오독 의심)이라 수량 후보에서 제외");
            return null;
        }
    }

    private BigDecimal resolveCurrentCycleStart() {
        // 모델이 전용 필드만 null로 반환한 경우 오른쪽 상단 자금 표의 정확한 라벨 행으로 보정한다.
        if (raw.currentCycleStart() != null) {
            return raw.currentCycleStart();
        }
        if (raw.capitalRows() == null) {
            return null;
        }
        return raw.capitalRows().stream()
                .filter(row -> labelContains(row, "현사이클시작"))
                .map(LabeledValue::value)
                .filter(value -> value != null && value.compareTo(BigDecimal.ZERO) > 0)
                .findFirst()
                .orElse(null);
    }

    private void checkCurrentCycleStartPlausibility(BigDecimal currentCycleStart, int holdings, BigDecimal avgPrice) {
        if (currentCycleStart == null) {
            return;
        }
        BigDecimal cashBalance = findRowValue(raw.capitalRows(), "잔금");
        // 잔금 표를 못 읽은 경우(우측 자금 표 전체 누락 등) 시즌 시작원금을 대체 기준선으로 사용
        BigDecimal expected = cashBalance != null
                ? cashBalance.add(avgPrice != null ? avgPrice.multiply(BigDecimal.valueOf(holdings)) : BigDecimal.ZERO)
                : raw.seasonStartCapital();
        if (expected == null || expected.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal ratio = currentCycleStart.divide(expected, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(CURRENT_CYCLE_START_RATIO_MAX) > 0 || ratio.compareTo(CURRENT_CYCLE_START_RATIO_MIN) < 0) {
            String basisLabel = cashBalance != null ? "잔금+보유평가액 추정치" : "시즌 시작원금 기준(잔금 미확보)";
            warn("OCR current_cycle_start 이상치 의심: current_cycle_start=" + currentCycleStart
                    + ", " + basisLabel + "=" + expected + " (비율=" + ratio + ") — 이미지 오판독 가능성, 시트·KISTA 값 확인 필요");
        }
    }

    private BigDecimal resolveCurrentCycleRealizedPnl() {
        // 모델이 인접한 "시즌N 실현수익" 행과 혼동해 current_cycle_realized_pnl을 잘못 채우는 사례가 있어,
        // 라벨로 구분되는 performance_rows를 우선 신뢰하고 직접 필드는 라벨 매칭 실패 시 폴백으로만 사용한다.
        BigDecimal fromRows = findRowValue(raw.performanceRows(), "현사이클실현수익");
        if (fromRows == null) {
            return raw.currentCycleRealizedPnl();
        }
        if (raw.currentCycleRealizedPnl() != null && fromRows.compareTo(raw.currentCycleRealizedPnl()) != 0) {
            warn("OCR 실현수익 불일치: current_cycle_realized_pnl=" + raw.currentCycleRealizedPnl()
                    + ", performance_rows 기준=" + fromRows + " — \"현사이클 실현수익\"/\"시즌N 실현수익\" 혼동 가능성, performance_rows 값 사용");
        }
        return fromRows;
    }

    // 공백을 무시한 라벨 부분 일치로 첫 번째 non-null 값 조회
    private static BigDecimal findRowValue(List<LabeledValue> rows, String normalizedLabel) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> labelContains(row, normalizedLabel))
                .map(LabeledValue::value)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean labelContains(LabeledValue row, String normalizedLabel) {
        return row.label() != null && row.label().replaceAll("\\s+", "").contains(normalizedLabel);
    }

    private static boolean isPositive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean isMagnitudeMismatch(int a, int b) {
        return a >= b * (long) HOLDINGS_MAGNITUDE_MISMATCH_RATIO
                || b >= a * (long) HOLDINGS_MAGNITUDE_MISMATCH_RATIO;
    }
}
