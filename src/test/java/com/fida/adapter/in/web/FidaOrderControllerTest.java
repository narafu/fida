package com.fida.adapter.in.web;

import com.fida.domain.model.KistaResult;
import com.fida.domain.port.in.ProcessImagesUseCase;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 병렬 실행 시 공유 목 빈 상태가 섞이므로 메서드를 순차 실행
@Execution(ExecutionMode.SAME_THREAD)
@WebMvcTest(FidaOrderController.class)
@DisplayName("FidaOrderController 테스트")
class FidaOrderControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ProcessTradingRecordUseCase useCase;
    @MockitoBean ProcessImagesUseCase processImages;
    // GlobalExceptionHandler 의존 빈
    @MockitoBean NotifyPort notifyPort;

    private static final UUID ID = UUID.randomUUID();

    private KistaResult result(LocalDate date) {
        return new KistaResult(ID, date, "SOXL", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN, 3, List.of());
    }

    @Test
    @DisplayName("POST /orders는 204를 반환하고 파이프라인을 1회 실행한다")
    void trigger_returns204() throws Exception {
        mvc.perform(post("/api/fida/orders")).andExpect(status().isNoContent());

        verify(useCase).process();
    }

    @Test
    @DisplayName("from-image는 바이트·날짜를 전달하고 KISTA id를 반환한다")
    void fromImage_withDate() throws Exception {
        byte[] bytes = {1, 2, 3};
        LocalDate date = LocalDate.of(2026, 7, 10);
        when(processImages.process(eq(bytes), eq(date))).thenReturn(result(date));

        mvc.perform(multipart("/api/fida/orders/from-image")
                        .file(new MockMultipartFile("image", "a.png", "image/png", bytes))
                        .param("date", "2026-07-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.tradeDate").value("2026-07-10"));
    }

    @Test
    @DisplayName("date 미지정 시 오늘 날짜를 사용한다")
    void fromImage_withoutDate_usesToday() throws Exception {
        byte[] bytes = {1};
        when(processImages.process(any(), any())).thenReturn(result(LocalDate.now()));

        mvc.perform(multipart("/api/fida/orders/from-image")
                        .file(new MockMultipartFile("image", "a.png", "image/png", bytes)))
                .andExpect(status().isOk());

        // 자정 경계 flakiness 방지를 위해 호출 후 날짜와 비교
        verify(processImages).process(eq(bytes), eq(LocalDate.now()));
    }

    @Test
    @DisplayName("빈 이미지는 400이고 파이프라인을 호출하지 않는다")
    void fromImage_emptyImage_returns400() throws Exception {
        mvc.perform(multipart("/api/fida/orders/from-image")
                        .file(new MockMultipartFile("image", "a.png", "image/png", new byte[0])))
                .andExpect(status().isBadRequest());

        verify(processImages, never()).process(any(), any());
    }

    @Test
    @DisplayName("image 파트 누락 시 4xx이고 파이프라인을 호출하지 않는다")
    void fromImage_missingImage_returns4xx() throws Exception {
        mvc.perform(multipart("/api/fida/orders/from-image").param("date", "2026-07-10"))
                .andExpect(status().is4xxClientError());

        verify(processImages, never()).process(any(), any());
    }
}
