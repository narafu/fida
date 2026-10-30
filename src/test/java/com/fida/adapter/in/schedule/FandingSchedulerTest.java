package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.HeartbeatPort;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

@DisplayName("FandingScheduler 테스트")
class FandingSchedulerTest {

    @Test
    @DisplayName("run()은 ProcessTradingRecordUseCase.process()를 위임 호출한다")
    void run_delegates_to_use_case() {
        boolean[] called = {false};
        ProcessTradingRecordUseCase stub = () -> called[0] = true;
        FandingScheduler scheduler = new FandingScheduler(stub, mock(NotifyPort.class), mock(HeartbeatPort.class), mock(TaskScheduler.class));

        scheduler.run();

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("run() 성공 시 heartbeatPort.ping()을 호출한다")
    void run_pings_heartbeat_on_success() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        HeartbeatPort heartbeatPort = mock(HeartbeatPort.class);
        FandingScheduler scheduler = new FandingScheduler(useCase, mock(NotifyPort.class), heartbeatPort, mock(TaskScheduler.class));

        scheduler.run();

        verify(heartbeatPort).ping();
    }

    @Test
    @DisplayName("run() 실패 시 Telegram 알림을 전송하고 예외를 삼키며 heartbeatPort.ping()은 호출하지 않는다")
    void run_notifies_and_swallows_when_process_fails() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        NotifyPort notifyPort = mock(NotifyPort.class);
        HeartbeatPort heartbeatPort = mock(HeartbeatPort.class);
        RuntimeException cause = new RuntimeException("스크래핑 실패");
        doThrow(cause).when(useCase).process();
        FandingScheduler scheduler = new FandingScheduler(useCase, notifyPort, heartbeatPort, mock(TaskScheduler.class));

        scheduler.run();

        verify(notifyPort).notifyApplicationFailure(eq("FIDA scheduler"), eq(cause));
        verify(heartbeatPort, never()).ping();
    }

    @Test
    @DisplayName("run() 실패 시 약 20분 뒤로 재시도를 1회 예약한다")
    void run_schedules_retry_after_20_minutes_on_failure() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        doThrow(new RuntimeException("실패")).when(useCase).process();
        FandingScheduler scheduler = new FandingScheduler(useCase, mock(NotifyPort.class), mock(HeartbeatPort.class), taskScheduler);
        Instant before = Instant.now();

        scheduler.run();

        var instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), instantCaptor.capture());
        assertThat(instantCaptor.getValue()).isBetween(before.plusSeconds(19 * 60), Instant.now().plusSeconds(21 * 60));
    }

    @Test
    @DisplayName("재시도가 성공하면 process()가 총 2회 호출되고 heartbeat는 1회 핑한다")
    void retry_success_pings_heartbeat_once() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        HeartbeatPort heartbeatPort = mock(HeartbeatPort.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        doThrow(new RuntimeException("실패")).doNothing().when(useCase).process();
        FandingScheduler scheduler = new FandingScheduler(useCase, mock(NotifyPort.class), heartbeatPort, taskScheduler);
        scheduler.run();
        var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        runnableCaptor.getValue().run();

        verify(useCase, times(2)).process();
        verify(heartbeatPort, times(1)).ping();
    }

    @Test
    @DisplayName("재시도도 실패하면 재시도 출처로 알림하고 추가 예약은 하지 않는다")
    void retry_failure_notifies_without_rescheduling() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        NotifyPort notifyPort = mock(NotifyPort.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        RuntimeException cause = new RuntimeException("계속 실패");
        doThrow(cause).when(useCase).process();
        FandingScheduler scheduler = new FandingScheduler(useCase, notifyPort, mock(HeartbeatPort.class), taskScheduler);
        scheduler.run();
        var runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        runnableCaptor.getValue().run();

        verify(notifyPort).notifyApplicationFailure(eq("FIDA scheduler 재시도"), eq(cause));
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("run() 성공 시 재시도를 예약하지 않는다")
    void run_does_not_schedule_retry_on_success() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        FandingScheduler scheduler = new FandingScheduler(mock(ProcessTradingRecordUseCase.class),
                mock(NotifyPort.class), mock(HeartbeatPort.class), taskScheduler);

        scheduler.run();

        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("run()은 화~토 07:00 KST cron과 Asia/Seoul 타임존으로 고정되어야 한다")
    void run_has_correct_cron_and_timezone() throws NoSuchMethodException {
        Scheduled annotation = FandingScheduler.class.getDeclaredMethod("run")
                .getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("0 0 7 * * TUE-SAT");
        assertThat(annotation.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("FandingScheduler는 @Profile(\"!job\")으로 job 프로필에서 비활성화되어야 한다")
    void scheduler_is_disabled_in_job_profile() {
        Profile profile = FandingScheduler.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!job");
    }

    @Test
    @DisplayName("FandingScheduler는 fida.scheduler.enabled=false이면 비활성화되어야 한다")
    void scheduler_can_be_disabled_by_property() {
        ConditionalOnProperty condition = FandingScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("fida.scheduler.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
