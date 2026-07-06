package com.fida.adapter.out.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeminiQuotaTracker 테스트")
class GeminiQuotaTrackerTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("recordRequest는 일일 사용량을 1 증가시키고 잔여 한도를 계산한다")
    void recordRequest_increments_daily_usage_and_returns_remaining_quota() {
        var tracker = new GeminiQuotaTracker(tempDir.resolve("quota.txt"), 20, () -> LocalDate.of(2026, 7, 7));

        GeminiQuotaTracker.QuotaStatus status = tracker.recordRequest();

        assertThat(status.remaining()).isEqualTo(19);
        assertThat(status.limit()).isEqualTo(20);
    }

    @Test
    @DisplayName("recordRequest는 같은 날짜의 기존 사용량을 이어서 계산한다")
    void recordRequest_continues_existing_daily_usage() {
        var tracker = new GeminiQuotaTracker(tempDir.resolve("quota.txt"), 20, () -> LocalDate.of(2026, 7, 7));
        tracker.recordRequest();

        GeminiQuotaTracker.QuotaStatus status = tracker.recordRequest();

        assertThat(status.remaining()).isEqualTo(18);
        assertThat(status.limit()).isEqualTo(20);
    }

    @Test
    @DisplayName("recordRequest는 날짜가 바뀌면 사용량을 초기화한다")
    void recordRequest_resets_usage_on_new_day() {
        final LocalDate[] today = {LocalDate.of(2026, 7, 7)};
        var tracker = new GeminiQuotaTracker(tempDir.resolve("quota.txt"), 20, () -> today[0]);
        tracker.recordRequest();
        today[0] = LocalDate.of(2026, 7, 8);

        GeminiQuotaTracker.QuotaStatus status = tracker.recordRequest();

        assertThat(status.remaining()).isEqualTo(19);
        assertThat(status.limit()).isEqualTo(20);
    }
}
