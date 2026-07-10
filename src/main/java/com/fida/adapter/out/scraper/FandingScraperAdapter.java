package com.fida.adapter.out.scraper;

import com.fida.domain.model.ScrapedPost;
import com.fida.domain.port.out.ScraperPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
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
        } catch (HttpStatusCodeException e) {
            throw new ScraperException("playwright-server 호출 실패: " + e.getStatusCode()
                    + " body=" + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new ScraperException("playwright-server 호출 실패: " + e.getMessage(), e);
        }
        validate(response);
        return toScrapedPost(response);
    }

    private void validate(ScrapeResponse response) {
        if (response == null || !response.success()) {
            String error = response != null ? response.error() : "null 응답";
            throw new ScraperException("스크래핑 실패: " + error);
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
        return resolveDateFromTitle(title, LocalDate.now());
    }

    LocalDate resolveDateFromTitle(String title, LocalDate today) {
        if (title == null) return null;
        var m = TITLE_DATE_PATTERN.matcher(title);
        if (!m.find()) return null;
        try {
            LocalDate candidate = LocalDate.of(today.getYear(),
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            // 연초에 전년도 연말 게시글을 처리하는 경우: 6개월 초과 미래 날짜는 전년도로 해석
            if (candidate.isAfter(today.plusMonths(6))) {
                candidate = candidate.minusYears(1);
            }
            return candidate;
        } catch (DateTimeException e) {
            return null;
        }
    }

    record ScrapeResponse(boolean success, String postTitle, String postDate,
                          String postUrl, List<ImageData> images, String error) {}

    record ImageData(String base64, String mimeType) {}
}
