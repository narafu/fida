package com.fida.adapter.in.web;

import com.fida.adapter.out.ocr.OcrException;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("GlobalExceptionHandler 테스트")
class GlobalExceptionHandlerTest {

    private final NotifyPort notifyPort = mock(NotifyPort.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(notifyPort);

    @Test
    @DisplayName("IllegalStateException은 500으로 변환하고 텔레그램 알림을 전송한다")
    void illegalState_returns_500_and_notifies() {
        var detail = handler.handleIllegalState(new IllegalStateException("KISTA 응답이 null입니다"));

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(detail.getDetail()).contains("KISTA 응답이 null입니다");
        verify(notifyPort).notifyApplicationFailure(eq("HTTP internal state"), any(IllegalStateException.class));
    }

    @Test
    @DisplayName("OcrException은 503으로 변환하고 중복 알림은 보내지 않는다")
    void ocrException_returns_503_without_duplicate_notify() {
        var detail = handler.handleOcr(new OcrException("Gemini JSON 파싱 실패"));

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        verifyNoInteractions(notifyPort);
    }
}
