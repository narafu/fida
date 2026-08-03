package com.fida.domain.model;

import java.time.LocalDate;

public record TradingRecord(
        LocalDate date,
        String postTitle,
        String postUrl,
        ParsedOrder order
) {
    public static TradingRecord of(ScrapedPost post, ParsedOrder order) {
        return new TradingRecord(post.postDate(), post.postTitle(), post.postUrl(), order);
    }
}
