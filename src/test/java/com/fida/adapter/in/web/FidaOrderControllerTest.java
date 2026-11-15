package com.fida.adapter.in.web;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 병렬 실행 시 공유 목 빈 상태가 섞이므로 메서드를 순차 실행
@Execution(ExecutionMode.SAME_THREAD)
@WebMvcTest(FidaOrderController.class)
@DisplayName("FidaOrderController 테스트")
class FidaOrderControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ProcessTradingRecordUseCase useCase;
    // GlobalExceptionHandler 의존 빈
    @MockitoBean NotifyPort notifyPort;

    @Test
    @DisplayName("POST /orders는 204를 반환하고 파이프라인을 1회 실행한다")
    void trigger_returns204() throws Exception {
        mvc.perform(post("/api/fida/orders")).andExpect(status().isNoContent());

        verify(useCase).process();
    }
}
