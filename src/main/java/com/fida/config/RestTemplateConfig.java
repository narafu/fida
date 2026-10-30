package com.fida.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .build();
    }

    // playwright 스크래핑 전용 — execFile 170s보다 길게 두어 스크래퍼 쪽 타임아웃 오류 바디를 받는다.
    // 공용 빈을 늘리면 Gemini·KISTA·텔레그램 호출까지 길어져 실행이 07:20 재시도와 겹칠 수 있어 분리
    // (주입은 필드명 scraperRestTemplate으로 이름 매칭)
    @Bean
    public RestTemplate scraperRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(180))
                .build();
    }
}
