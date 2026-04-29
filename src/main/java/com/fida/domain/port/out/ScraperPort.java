package com.fida.domain.port.out;

import com.fida.domain.model.ScrapedPost;

public interface ScraperPort {
    ScrapedPost scrape();
}
