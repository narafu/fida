package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FandingScheduler {

    private final ProcessTradingRecordUseCase useCase;

    public FandingScheduler(ProcessTradingRecordUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() {
        useCase.process();
    }
}
