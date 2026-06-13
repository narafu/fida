package com.fida.adapter.in.web;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FIDA 주문", description = "매매 기록 처리 트리거")
@RestController
@RequestMapping("/api/fida")
@RequiredArgsConstructor
public class FidaOrderController {

    private final ProcessTradingRecordUseCase useCase;

    // 스크래핑 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송 파이프라인 트리거
    @Operation(
            summary = "매매 실행 트리거",
            description = "스크래핑 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송 파이프라인 실행"
    )
    @ApiResponse(responseCode = "204", description = "파이프라인 처리 완료 (응답 바디 없음)")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trigger() {
        useCase.process();
    }
}
