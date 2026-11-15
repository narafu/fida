package com.fida.adapter.out.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fida.domain.model.ParsedOrder;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OcrArchive 테스트")
class OcrArchiveTest {

    @TempDir
    Path tmp;

    private OcrArchive archiveAt(Path path) {
        OcrArchive archive = new OcrArchive();
        ReflectionTestUtils.setField(archive, "archivePath", path.toString());
        return archive;
    }

    @Test
    @DisplayName("이미지·응답이 KST 날짜 폴더 아래 저장되고 60일 초과 날짜 폴더만 삭제된다")
    void saves_under_kst_date_and_purges_expired() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Path expired = Files.createDirectories(tmp.resolve(today.minusDays(61).toString()).resolve("010101"));
        Path kept = Files.createDirectories(tmp.resolve(today.minusDays(59).toString()));
        Path other = Files.createDirectories(tmp.resolve("notes"));
        OcrArchive archive = archiveAt(tmp);

        Path run = archive.begin(List.of(new byte[]{1, 2}, new byte[]{3}));
        archive.saveResponse(run, 1, "raw-1");
        archive.saveResponse(run, 2, null); // 텍스트 없으면 저장 안 함

        assertThat(run.getParent()).isEqualTo(tmp.resolve(today.toString()));
        assertThat(Files.readAllBytes(run.resolve("image-1.bin"))).containsExactly(1, 2);
        assertThat(Files.readAllBytes(run.resolve("image-2.bin"))).containsExactly(3);
        assertThat(Files.readString(run.resolve("response-1.txt"))).isEqualTo("raw-1");
        assertThat(run.resolve("response-2.txt")).doesNotExist();
        assertThat(expired.getParent()).doesNotExist();
        assertThat(kept).exists();
        assertThat(other).exists();
    }

    @Test
    @DisplayName("같은 초 폴더가 있어도 덮어쓰지 않고 접미사 폴더를 만든다")
    void does_not_overwrite_same_second_dir() {
        OcrArchive archive = archiveAt(tmp);

        Path a = archive.begin(List.of(new byte[]{1}));
        Path b = archive.begin(List.of(new byte[]{2}));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).exists();
        assertThat(b).exists();
    }

    @Test
    @DisplayName("보관 경로가 막혀 있어도 analyze()는 정상 결과를 반환한다")
    void analyze_succeeds_when_archive_path_blocked() throws Exception {
        Path blocker = Files.createFile(tmp.resolve("blocker"));
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        NotifyPort notifyPort = mock(NotifyPort.class);
        GeminiClient client = new GeminiClient(restTemplate, notifyPort,
                new GeminiQuotaTracker(tmp.resolve("quota.json"), 20, LocalDate::now));
        ReflectionTestUtils.setField(client, "apiKey", "k");
        var adapter = new GeminiVisionAdapter(client, notifyPort, archiveAt(blocker.resolve("sub")));
        String body = new ObjectMapper().writeValueAsString(Map.of("candidates", List.of(
                Map.of("content", Map.of("parts", List.of(Map.of("text", OcrFixtures.load("fenced-empty"))))))));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ParsedOrder result = adapter.analyze(List.of(new byte[]{1}));

        assertThat(result.holdings()).isZero();
    }
}
