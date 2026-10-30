package com.fida.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fida.domain.exception.OcrException;
import com.fida.domain.exception.ScraperException;
import com.fida.domain.exception.SheetException;
import com.fida.domain.exception.NotifyException;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final NotifyPort notifyPort;

    @ExceptionHandler(ScraperException.class)
    public ProblemDetail handleScraper(ScraperException ex) {
        log.warn("ScraperException: {}", ex.getMessage(), ex);
        notifyPort.notifyApplicationFailure("HTTP scraper", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("Scraper Unavailable");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        notifyPort.notifyApplicationFailure("HTTP invalid request", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid Request");
        return detail;
    }

    // KISTA 응답 불일치·null 응답 등 내부 상태 오류 — 500 + 텔레그램 알림
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.error("IllegalStateException: {}", ex.getMessage(), ex);
        notifyPort.notifyApplicationFailure("HTTP internal state", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        detail.setTitle("Internal State Error");
        return detail;
    }

    // OCR 실패 — GeminiVisionAdapter가 이미 notifyGeminiError로 알림 전송하므로 상태 변환만 수행
    @ExceptionHandler(OcrException.class)
    public ProblemDetail handleOcr(OcrException ex) {
        log.warn("OcrException: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("OCR Failed");
        return detail;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 외부 API(KISTA·Gemini 등)가 4xx/5xx를 반환한 경우 — 응답 바디 전체를 JSON으로 포함
    @ExceptionHandler(HttpStatusCodeException.class)
    public ProblemDetail handleExternalHttpError(HttpStatusCodeException ex) {
        log.warn("외부 API HTTP 오류 {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        notifyPort.notifyApplicationFailure("HTTP external API", ex);
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
        notifyPort.notifyApplicationFailure("HTTP external network", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("External API Unavailable");
        return detail;
    }

    // 시트 기록 실패 — 503 + 텔레그램 알림
    @ExceptionHandler(SheetException.class)
    public ProblemDetail handleSheet(SheetException ex) {
        log.warn("SheetException: {}", ex.getMessage(), ex);
        notifyPort.notifyApplicationFailure("HTTP sheet", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("Sheet Unavailable");
        return detail;
    }

    // 텔레그램 자체 실패 — 알림 채널이 죽었으므로 재알림 없이 로그만
    @ExceptionHandler(NotifyException.class)
    public ProblemDetail handleNotify(NotifyException ex) {
        log.error("NotifyException: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "텔레그램 알림 실패");
        detail.setTitle("Notify Unavailable");
        return detail;
    }

    // 그 외 예외 — 500 + 텔레그램 알림
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        // Spring MVC 표준 요청 오류·클라이언트 연결 끊김은 다시 던져 DefaultHandlerExceptionResolver가 원래 4xx 상태·헤더로 처리하게 함
        if (ex instanceof ErrorResponse || ex instanceof TypeMismatchException
                || ex instanceof HttpMessageNotReadableException || ex instanceof MultipartException
                || ex instanceof IOException) {
            throw ex;
        }
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        notifyPort.notifyApplicationFailure("HTTP unexpected", ex);
        // 원인 메시지는 로그·텔레그램에만 남기고 응답에는 고정 문구만 노출
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        detail.setTitle("Internal Server Error");
        return detail;
    }
}
