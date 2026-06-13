package com.fida.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record FromUrlRequest(
        @NotBlank String postUrl // fanding 상세 페이지 URL
) {}
