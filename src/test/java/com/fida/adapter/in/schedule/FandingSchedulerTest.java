package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("FandingScheduler 테스트")
class FandingSchedulerTest {

    @Test
    @DisplayName("run()은 ProcessTradingRecordUseCase.process()를 위임 호출한다")
    void run_delegates_to_use_case() {
        boolean[] called = {false};
        ProcessTradingRecordUseCase stub = () -> called[0] = true;
        FandingScheduler scheduler = new FandingScheduler(stub, mock(NotifyPort.class));

        scheduler.run();

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("run() 실패 시 Telegram 알림을 전송하고 예외를 삼킨다")
    void run_notifies_and_swallows_when_process_fails() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        NotifyPort notifyPort = mock(NotifyPort.class);
        RuntimeException cause = new RuntimeException("스크래핑 실패");
        doThrow(cause).when(useCase).process();
        FandingScheduler scheduler = new FandingScheduler(useCase, notifyPort);

        scheduler.run();

        verify(notifyPort).notifyApplicationFailure(eq("FIDA scheduler"), eq(cause));
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
}
