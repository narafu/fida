package com.fida.adapter.out.heartbeat;

import com.fida.domain.port.out.HeartbeatPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatAdapter implements HeartbeatPort {

    private final RestTemplate restTemplate; // 기존 범용 빈(RestTemplateConfig) 재사용 — 빈 이름/필드명 일치 필수

    @Value("${heartbeat.url:}")
    private String url; // non-final — @Value + final 조합은 CI에서 필드 주입 실패 (KistaAdapter/TelegramAdapter 컨벤션)

    @Override
    public void ping() {
        // url 미설정 시 핑 생략 — dead-man's switch를 쓰지 않는 환경(로컬 등)에서 무해하게 동작
        if (url == null || url.isBlank()) return;
        try {
            restTemplate.getForObject(url, String.class);
            log.info("heartbeat 핑 완료");
        } catch (Exception e) {
            // 핑 실패는 매매 흐름에 영향 없어야 함 — 로그만 남기고 삼킴
            log.warn("heartbeat 핑 실패: {}", e.getMessage());
        }
    }
}
