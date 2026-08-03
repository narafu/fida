package com.fida.domain.port.out;

public interface HeartbeatPort {
    // 스케줄러 정상 실행 신호 — 외부 감시(healthchecks.io)가 시간 내 신호 없으면 알림 (dead-man's switch)
    void ping();
}
