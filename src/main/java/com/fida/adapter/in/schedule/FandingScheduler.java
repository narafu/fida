package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!job")
@RequiredArgsConstructor
public class FandingScheduler {

    private final ProcessTradingRecordUseCase useCase;

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() {
        useCase.process();
    }
}
