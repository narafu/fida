package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!job")
@ConditionalOnProperty(name = "fida.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class FandingScheduler {

    private final ProcessTradingRecordUseCase useCase;
    private final NotifyPort notifyPort;

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() {
        try {
            useCase.process();
        } catch (Exception e) {
            log.error("FIDA scheduler 실행 실패", e);
            safeNotify(e);
        }
    }

    private void safeNotify(Exception cause) {
        try {
            notifyPort.notifyApplicationFailure("FIDA scheduler", cause);
        } catch (Exception notifyError) {
            log.warn("FIDA scheduler 실패 알림 전송 실패: {}", notifyError.getMessage());
        }
    }
}
