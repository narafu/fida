package com.fida.adapter.out.scraper;

import com.fida.domain.model.ScrapedPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}
