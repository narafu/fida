package com.fida.adapter.out.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// OCR 회귀 자료(원본 이미지·Gemini 원시 응답) 보관 — 실패해도 파이프라인에 영향 없도록 모든 IO 예외를 삼킨다
@Slf4j
@Component
public class OcrArchive {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int RETENTION_DAYS = 60;

    @Value("${ocr.archive-path:/state/ocr-archive}")
    private String archivePath; // 보관 루트 (<root>/<yyyy-MM-dd>/<HHmmss>/)

    // 실행 폴더를 만들고 이미지를 저장한 뒤 폴더를 반환한다 — 실패하면 null (이후 saveResponse는 생략)
    public Path begin(List<byte[]> images) {
        try {
            Path root = Path.of(archivePath);
            LocalDateTime now = LocalDateTime.now(KST);
            // 보관 기간이 지난 날짜 폴더 정리
            purgeExpired(root, now.toLocalDate());
            Path run = createRunDir(root.resolve(now.toLocalDate().toString()),
                    now.format(DateTimeFormatter.ofPattern("HHmmss")));
            saveImages(run, images);
            return run;
        } catch (Exception e) {
            log.warn("OCR 이미지 보관 실패 — 무시하고 계속: {}", e.toString());
            return null;
        }
    }

    // 이미지 저장 중 실패해도 실행 폴더는 유지해 응답 보관은 계속되게 한다
    private void saveImages(Path run, List<byte[]> images) {
        try {
            for (int i = 0; i < images.size(); i++) {
                Files.write(run.resolve("image-" + (i + 1) + ".bin"), images.get(i));
            }
        } catch (Exception e) {
            log.warn("OCR 이미지 일부 보관 실패 — 응답 보관은 계속: {}", e.toString());
        }
    }

    // Gemini 원시 응답 텍스트를 시도별 파일로 저장
    public void saveResponse(Path run, int attempt, String text) {
        if (run == null || text == null) {
            return;
        }
        try {
            Files.writeString(run.resolve("response-" + attempt + ".txt"), text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("OCR 응답 보관 실패 — 무시하고 계속: {}", e.toString());
        }
    }

    // 같은 초 폴더가 있으면 -2, -3 ... 접미사로 덮어쓰기를 피한다
    private Path createRunDir(Path dateDir, String time) throws IOException {
        Files.createDirectories(dateDir);
        for (int n = 1; ; n++) {
            try {
                return Files.createDirectory(dateDir.resolve(n == 1 ? time : time + "-" + n));
            } catch (FileAlreadyExistsException e) {
                // 다음 접미사로 재시도
            }
        }
    }

    // 루트 바로 아래 yyyy-MM-dd 폴더 중 60일보다 오래된 것만 재귀 삭제 — 다른 이름은 건드리지 않는다
    private void purgeExpired(Path root, LocalDate today) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(dir -> {
                try {
                    if (LocalDate.parse(dir.getFileName().toString()).isBefore(today.minusDays(RETENTION_DAYS))) {
                        deleteRecursively(dir);
                    }
                } catch (DateTimeParseException ignored) {
                    // 날짜 형식이 아닌 폴더는 보존
                } catch (Exception e) {
                    // 폴더 하나의 삭제 실패가 나머지 정리를 막지 않게 한다
                    log.warn("OCR 보관 폴더 정리 실패 {} — 다음 폴더 계속: {}", dir, e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("OCR 보관 폴더 정리 실패 — 무시하고 계속: {}", e.toString());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            // 하위 항목부터 삭제 — 실패는 첫 예외로 전파돼 폴더 단위로 한 번만 로그
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
