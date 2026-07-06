package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("job")
@RequiredArgsConstructor
@Slf4j
public class FandingRunner implements ApplicationRunner {

    private final ProcessTradingRecordUseCase useCase;
    private final NotifyPort notifyPort;

    @Override
    public void run(ApplicationArguments args) {
        log.info("FIDA one-shot 실행 시작");
        try {
            useCase.process();
            log.info("FIDA one-shot 실행 완료");
        } catch (Exception e) {
            log.error("FIDA one-shot 실행 실패", e);
            safeNotify(e);
            throw e;
        }
    }

    private void safeNotify(Exception cause) {
        try {
            notifyPort.notifyApplicationFailure("FIDA one-shot", cause);
        } catch (Exception notifyError) {
            log.warn("FIDA one-shot 실패 알림 전송 실패: {}", notifyError.getMessage());
        }
    }
}
