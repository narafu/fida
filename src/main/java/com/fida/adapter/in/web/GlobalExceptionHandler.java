package com.fida.adapter.in.web;

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

    // KISTA API가 4xx/5xx를 반환한 경우 — 응답 바디 포함해 그대로 전달
    @ExceptionHandler(HttpStatusCodeException.class)
    public ProblemDetail handleKistaHttpError(HttpStatusCodeException ex) {
        log.warn("KISTA HTTP 오류 {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getResponseBodyAsString());
        detail.setTitle("KISTA Error");
        return detail;
    }

    // KISTA 연결 실패 등 네트워크 오류
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleKistaNetworkError(RestClientException ex) {
        log.warn("KISTA 네트워크 오류: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("KISTA Unavailable");
        return detail;
    }
}
