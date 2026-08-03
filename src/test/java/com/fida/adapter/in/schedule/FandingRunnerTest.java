package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("FandingRunner 테스트")
class FandingRunnerTest {

    @Test
    @DisplayName("run()은 ProcessTradingRecordUseCase.process()를 위임 호출한다")
    void run_delegates_to_use_case() throws Exception {
        boolean[] called = {false};
        ProcessTradingRecordUseCase stub = () -> called[0] = true;
        FandingRunner runner = new FandingRunner(stub, mock(NotifyPort.class));

        runner.run(mock(ApplicationArguments.class));

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("run() 실패 시 Telegram 알림을 전송하고 예외를 다시 던진다")
    void run_notifies_and_rethrows_when_process_fails() {
        ProcessTradingRecordUseCase useCase = mock(ProcessTradingRecordUseCase.class);
        NotifyPort notifyPort = mock(NotifyPort.class);
        RuntimeException cause = new RuntimeException("스크래핑 실패");
        doThrow(cause).when(useCase).process();
        FandingRunner runner = new FandingRunner(useCase, notifyPort);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isSameAs(cause);

        verify(notifyPort).notifyApplicationFailure(eq("FIDA one-shot"), eq(cause));
    }

    @Test
    @DisplayName("FandingRunner는 @Profile(\"job\")으로 제한되어야 한다")
    void runner_is_restricted_to_job_profile() {
        Profile profile = FandingRunner.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("job");
    }
}
