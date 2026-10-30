package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.HeartbeatPort;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@Profile("!job")
@ConditionalOnProperty(name = "fida.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class FandingScheduler {

    // 07:00 실패 시 1회 재시도 지연 — 07:20 기동, 배포 가드(06:50~07:45 KST) 안에서 끝나도록 맞춤
    static final Duration RETRY_DELAY = Duration.ofMinutes(20);

    private final ProcessTradingRecordUseCase useCase;
    private final NotifyPort notifyPort;
    private final HeartbeatPort heartbeatPort;
    private final TaskScheduler taskScheduler;

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() {
        var startedAt = Instant.now(); // 재시도 시각을 실패 시점이 아닌 기동 시점 기준으로 고정
        if (!execute("FIDA scheduler")) {
            // 시트·KISTA 모두 멱등이라 재실행 안전 — 재시도는 1회만, 재시도 실패 시 알림만 남김
            taskScheduler.schedule(() -> execute("FIDA scheduler 재시도"), startedAt.plus(RETRY_DELAY));
        }
    }

    private boolean execute(String source) {
        try {
            useCase.process();
        } catch (Exception e) {
            log.error("{} 실행 실패", source, e);
            notifyPort.notifyApplicationFailure(source, e);
            return false;
        }
        heartbeatPort.ping(); // 스케줄 실행 성공 신호 — dead-man's switch, 핑 실패가 재시도를 유발하지 않도록 try 밖
        return true;
    }
}
