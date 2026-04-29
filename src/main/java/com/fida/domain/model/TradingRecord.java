package com.fida.domain.model;

import java.time.LocalDate;

public class TradingRecord {

    private final LocalDate date;
    private final String postTitle;
    private final String postUrl;
    private final ParsedOrder order;

    private TradingRecord(LocalDate date, String postTitle, String postUrl, ParsedOrder order) {
        this.date = date;
        this.postTitle = postTitle;
        this.postUrl = postUrl;
        this.order = order;
    }

    public static TradingRecord of(ScrapedPost post, ParsedOrder order) {
        return new TradingRecord(post.postDate(), post.postTitle(), post.postUrl(), order);
    }

    public LocalDate date() { return date; }
    public String postTitle() { return postTitle; }
    public String postUrl() { return postUrl; }
    public ParsedOrder order() { return order; }
}
