package com.fida.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fida.adapter.out.scraper.ScraperException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.NoSuchElementException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Resource Not Found");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList()
                .toString();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setTitle("Validation Failed");
        return detail;
    }

    @ExceptionHandler(ScraperException.class)
    public ProblemDetail handleScraper(ScraperException ex) {
        log.warn("ScraperException: {}", ex.getMessage(), ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("Scraper Unavailable");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid Request");
        return detail;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 외부 API(KISTA·Gemini 등)가 4xx/5xx를 반환한 경우 — 응답 바디에서 메시지 추출
    @ExceptionHandler(HttpStatusCodeException.class)
    public ProblemDetail handleExternalHttpError(HttpStatusCodeException ex) {
        String message = extractMessage(ex.getResponseBodyAsString(), ex.getMessage());
        log.warn("외부 API HTTP 오류 {}: {}", ex.getStatusCode(), message);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), message);
        detail.setTitle("External API Error");
        return detail;
    }

    // 외부 API 연결 실패 등 네트워크 오류
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleExternalNetworkError(RestClientException ex) {
        log.warn("외부 API 네트워크 오류: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("External API Unavailable");
        return detail;
    }

    // JSON 응답 바디에서 메시지 필드 추출 (실패 시 fallback)
    private String extractMessage(String body, String fallback) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // Gemini: { error: { message: "..." } }
            JsonNode msg = root.path("error").path("message");
            if (!msg.isMissingNode()) return msg.asText();
            // KISTA ProblemDetail: { detail: "..." }
            JsonNode detail = root.path("detail");
            if (!detail.isMissingNode()) return detail.asText();
        } catch (Exception ignored) {}
        return fallback;
    }
}
