package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("FandingRunner 테스트")
class FandingRunnerTest {

    @Test
    @DisplayName("run()은 ProcessTradingRecordUseCase.process()를 위임 호출한다")
    void run_delegates_to_use_case() throws Exception {
        boolean[] called = {false};
        ProcessTradingRecordUseCase stub = () -> called[0] = true;
        FandingRunner runner = new FandingRunner(stub);

        runner.run(mock(ApplicationArguments.class));

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("FandingRunner는 @Profile(\"job\")으로 제한되어야 한다")
    void runner_is_restricted_to_job_profile() {
        Profile profile = FandingRunner.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("job");
    }
}
