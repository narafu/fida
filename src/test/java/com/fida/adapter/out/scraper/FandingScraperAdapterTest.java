package com.fida.adapter.out.scraper;

import com.fida.domain.model.ScrapedPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

@DisplayName("FandingScraperAdapter 테스트")
class FandingScraperAdapterTest {

    private static final String SCRAPER_URL = "http://playwright-server:3000/scrape";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private FandingScraperAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateBuilder().build();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        adapter = new FandingScraperAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "scraperUrl", SCRAPER_URL);
    }

    @Test
    @DisplayName("성공 응답 시 ScrapedPost를 올바르게 파싱한다")
    void scrape_parses_success_response() {
        byte[] imageBytes = {10, 20, 30};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String json = """
                {
                  "success": true,
                  "postTitle": "매매표 2024-01-15",
                  "postDate": "2024-01-15",
                  "postUrl": "https://fanding.kr/post/1",
                  "images": [{"base64": "%s", "mimeType": "image/png"}]
                }
                """.formatted(base64);

        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ScrapedPost result = adapter.scrape();

        assertThat(result.postTitle()).isEqualTo("매매표 2024-01-15");
        assertThat(result.postDate().toString()).isEqualTo("2024-01-15");
        assertThat(result.postUrl()).isEqualTo("https://fanding.kr/post/1");
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().get(0)).isEqualTo(imageBytes);
        mockServer.verify();
    }

    @Test
    @DisplayName("success=false 응답 시 ScraperException을 던진다")
    void scrape_throws_on_failure_response() {
        String json = """
                {"success": false, "error": "로그인 실패"}
                """;

        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.scrape())
                .isInstanceOf(ScraperException.class)
                .hasMessageContaining("로그인 실패");
    }

    @Test
    @DisplayName("HTTP 500 응답 시 ScraperException을 던진다")
    void scrape_throws_on_server_error() {
        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.scrape())
                .isInstanceOf(ScraperException.class);
    }

    @Test
    @DisplayName("HTTP 500 응답 본문은 ScraperException 메시지에 포함된다")
    void scrape_includes_server_error_body_in_exception_message() {
        String body = """
                {"success":false,"error":"Command failed","stdout":"{\\"success\\":false,\\"error\\":\\"로그인 실패\\"}","stderr":"trace"}
                """;
        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> adapter.scrape())
                .isInstanceOf(ScraperException.class)
                .hasMessageContaining("로그인 실패")
                .hasMessageContaining("stderr");
    }

    @Test
    @DisplayName("제목에 M/D 패턴 있으면 postDate보다 제목 날짜를 우선한다")
    void scrape_prefers_title_date_over_postDate() {
        byte[] imageBytes = {10, 20, 30};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String json = """
                {
                  "success": true,
                  "postTitle": "Privacy 5/7 _ SOXL 매매기록",
                  "postDate": "2026-05-06",
                  "postUrl": "https://fanding.kr/post/2",
                  "images": [{"base64": "%s", "mimeType": "image/png"}]
                }
                """.formatted(base64);

        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ScrapedPost result = adapter.scrape();

        assertThat(result.postDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 5, 7));
    }

    @Test
    @DisplayName("제목에 M/D 패턴 없으면 postDate를 사용한다")
    void scrape_falls_back_to_postDate_when_no_title_date() {
        byte[] imageBytes = {10, 20, 30};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String json = """
                {
                  "success": true,
                  "postTitle": "SOXL 매매기록",
                  "postDate": "2026-05-06",
                  "postUrl": "https://fanding.kr/post/3",
                  "images": [{"base64": "%s", "mimeType": "image/png"}]
                }
                """.formatted(base64);

        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ScrapedPost result = adapter.scrape();

        assertThat(result.postDate()).isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    @DisplayName("제목에 M/D 없고 postDate도 없으면 오늘 날짜를 사용한다")
    void scrape_falls_back_to_today_when_no_date_available() {
        byte[] imageBytes = {10, 20, 30};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String json = """
                {
                  "success": true,
                  "postTitle": "SOXL 매매기록",
                  "postUrl": "https://fanding.kr/post/4",
                  "images": [{"base64": "%s", "mimeType": "image/png"}]
                }
                """.formatted(base64);

        mockServer.expect(requestTo(SCRAPER_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ScrapedPost result = adapter.scrape();

        assertThat(result.postDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("연초에 전년도 12월 게시글 제목을 처리하면 전년도 날짜로 해석한다")
    void resolveDateFromTitle_interprets_far_future_as_previous_year() {
        // 2027-01-01에 "12/31" 제목 처리 → 2027-12-31이 아닌 2026-12-31
        var result = adapter.resolveDateFromTitle("Privacy 12/31 _ SOXL 매매기록", LocalDate.of(2027, 1, 1));

        assertThat(result).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("제목 날짜가 오늘로부터 6개월 이내면 올해 날짜로 해석한다")
    void resolveDateFromTitle_keeps_current_year_for_near_dates() {
        var result = adapter.resolveDateFromTitle("Privacy 7/11 _ SOXL 매매기록", LocalDate.of(2026, 7, 10));

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 11));
    }
}
