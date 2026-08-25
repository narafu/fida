package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Supplier;

@Slf4j
@Component
public class GeminiQuotaTracker {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.daily-limit:20}")
    private int dailyLimit = 20; // Gemini 무료 티어 일일 요청 한도

    @Value("${gemini.quota-usage-path:/tmp/fida-gemini-quota-usage.json}")
    private String usagePath = "/tmp/fida-gemini-quota-usage.json"; // 일일 사용량 저장 파일

    private final Supplier<LocalDate> todaySupplier;

    public GeminiQuotaTracker() {
        this.todaySupplier = LocalDate::now;
    }

    GeminiQuotaTracker(Path usagePath, int dailyLimit, Supplier<LocalDate> todaySupplier) {
        this.usagePath = usagePath.toString();
        this.dailyLimit = dailyLimit;
        this.todaySupplier = todaySupplier;
    }

    synchronized QuotaStatus recordRequest() {
        LocalDate today = todaySupplier.get();
        UsageState state = readState();
        int used = (state != null && today.toString().equals(state.date())) ? state.used() : 0;
        int nextUsed = used + 1;
        writeState(new UsageState(today.toString(), nextUsed));
        return new QuotaStatus(Math.max(0, dailyLimit - nextUsed), dailyLimit);
    }

    private UsageState readState() {
        Path path = Path.of(usagePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(path), UsageState.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeState(UsageState state) {
        Path path = Path.of(usagePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(path.toFile(), state);
        } catch (Exception e) {
            // Quota 알림은 관측 보조 기능이므로 본 처리 실패 원인이 되면 안 되지만, 무음 실패는 원인 파악을 막으므로 로그는 남긴다.
            log.warn("Gemini quota 상태 파일 쓰기 실패 (path={}): {}", path, e.getMessage());
        }
    }

    record QuotaStatus(int remaining, int limit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageState(String date, int used) {}
}
