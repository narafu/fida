package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
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

    @Override
    public void run(ApplicationArguments args) {
        log.info("FIDA one-shot 실행 시작");
        useCase.process();
        log.info("FIDA one-shot 실행 완료");
    }
}
