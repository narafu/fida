package com.fida.adapter.out.scraper;

import com.fida.domain.model.ScrapedPost;
import com.fida.domain.port.out.ScraperPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FandingScraperAdapter implements ScraperPort {

    @Value("${fanding.scraper.url}")
    private String scraperUrl;

    private final RestTemplate restTemplate;

    @Override
    public ScrapedPost scrape() {
        ScrapeResponse response;
        try {
            response = restTemplate.getForObject(scraperUrl, ScrapeResponse.class);
        } catch (RestClientException e) {
            throw new ScraperException("playwright-server 호출 실패: " + e.getMessage(), e);
        }
        validate(response, "스크래핑 실패");
        return toScrapedPost(response);
    }

    private void validate(ScrapeResponse response, String contextMsg) {
        if (response == null || !response.success()) {
            String error = response != null ? response.error() : "null 응답";
            throw new ScraperException(contextMsg + ": " + error);
        }
    }

    private ScrapedPost toScrapedPost(ScrapeResponse response) {
        List<byte[]> images = response.images().stream()
                .map(img -> Base64.getDecoder().decode(img.base64()))
                .toList();
        LocalDate postDate = resolveDateFromTitle(response.postTitle());
        if (postDate == null && response.postDate() != null) {
            postDate = LocalDate.parse(response.postDate());
        }
        if (postDate == null) {
            postDate = LocalDate.now();
        }
        return new ScrapedPost(response.postTitle(), postDate, response.postUrl(), images);
    }

    private static final Pattern TITLE_DATE_PATTERN = Pattern.compile("(\\d{1,2})/(\\d{1,2})");

    private LocalDate resolveDateFromTitle(String title) {
        if (title == null) return null;
        var m = TITLE_DATE_PATTERN.matcher(title);
        if (!m.find()) return null;
        try {
            return LocalDate.of(LocalDate.now().getYear(),
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    record ScrapeResponse(boolean success, String postTitle, String postDate,
                          String postUrl, List<ImageData> images, String error) {}

    record ImageData(String base64, String mimeType) {}
}
