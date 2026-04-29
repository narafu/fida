package com.fida.domain.model;

import java.time.LocalDate;
import java.util.List;

public record ScrapedPost(
        String postTitle,
        LocalDate postDate,
        String postUrl,
        List<byte[]> images
) {}
