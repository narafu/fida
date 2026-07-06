package com.fida.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fida.adapter.out.scraper.ScraperException;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final NotifyPort notifyPort;

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        safeNotify("HTTP 404", ex);
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
        safeNotify("HTTP validation", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setTitle("Validation Failed");
        return detail;
    }

    @ExceptionHandler(ScraperException.class)
    public ProblemDetail handleScraper(ScraperException ex) {
        log.warn("ScraperException: {}", ex.getMessage(), ex);
        safeNotify("HTTP scraper", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("Scraper Unavailable");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        safeNotify("HTTP invalid request", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid Request");
        return detail;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 외부 API(KISTA·Gemini 등)가 4xx/5xx를 반환한 경우 — 응답 바디 전체를 JSON으로 포함
    @ExceptionHandler(HttpStatusCodeException.class)
    public ProblemDetail handleExternalHttpError(HttpStatusCodeException ex) {
        log.warn("외부 API HTTP 오류 {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        safeNotify("HTTP external API", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getMessage());
        detail.setTitle("External API Error");
        // 응답 바디가 JSON이면 파싱해서 포함, 아니면 원문 문자열로 포함
        try {
            detail.setProperty("externalError", objectMapper.readTree(ex.getResponseBodyAsString()));
        } catch (Exception ignored) {
            detail.setProperty("externalError", ex.getResponseBodyAsString());
        }
        return detail;
    }

    // 외부 API 연결 실패 등 네트워크 오류
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleExternalNetworkError(RestClientException ex) {
        log.warn("외부 API 네트워크 오류: {}", ex.getMessage());
        safeNotify("HTTP external network", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("External API Unavailable");
        return detail;
    }

    private void safeNotify(String stage, Exception cause) {
        try {
            notifyPort.notifyApplicationFailure(stage, cause);
        } catch (Exception notifyError) {
            log.warn("HTTP 오류 알림 전송 실패: {}", notifyError.getMessage());
        }
    }
}
