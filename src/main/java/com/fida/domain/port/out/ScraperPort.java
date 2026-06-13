package com.fida.domain.port.out;

import com.fida.domain.model.ScrapedPost;

public interface ScraperPort {
    ScrapedPost scrape();
    ScrapedPost scrapeFromUrl(String url); // 특정 fanding 상세 페이지에서 스크래핑
}
