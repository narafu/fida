package com.fida.adapter.out.scraper;

import com.fida.domain.model.ScrapedPost;
import com.fida.domain.port.out.ScraperPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FandingScraperAdapter implements ScraperPort {

    private final RestTemplate restTemplate;
    @Value("${scraper.url}")
    private String scraperUrl;

    @Override
    public ScrapedPost scrape() {
        ScrapeResponse response;
        try {
            response = restTemplate.getForObject(scraperUrl, ScrapeResponse.class);
        } catch (RestClientException e) {
            throw new ScraperException("playwright-server 호출 실패: " + e.getMessage(), e);
        }
        if (response == null || !response.success()) {
            String error = response != null ? response.error() : "null 응답";
            throw new ScraperException("스크래핑 실패: " + error);
        }
        List<byte[]> images = response.images().stream()
                .map(img -> Base64.getDecoder().decode(img.base64()))
                .toList();
        return new ScrapedPost(
                response.postTitle(),
                LocalDate.parse(response.postDate()),
                response.postUrl(),
                images
        );
    }

    record ScrapeResponse(boolean success, String postTitle, String postDate,
                          String postUrl, List<ImageData> images, String error) {}

    record ImageData(String base64, String mimeType) {}
}
