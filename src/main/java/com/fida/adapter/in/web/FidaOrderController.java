package com.fida.adapter.in.web;

import com.fida.domain.port.in.ProcessImagesUseCase;
import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

@Tag(name = "FIDA 주문", description = "매매 기록 처리 트리거")
@RestController
@RequestMapping("/api/fida")
@RequiredArgsConstructor
public class FidaOrderController {

    private final ProcessTradingRecordUseCase useCase;
    private final ProcessImagesUseCase processImages;

    // 스크래핑 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송 파이프라인 트리거
    @Operation(
            summary = "매매 실행 트리거 [로컬/Docker Compose 전용]",
            description = """
                    스크래핑 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송 파이프라인 실행.

                    ⚠️ 로컬/Docker Compose 환경 전용: fanding.kr SPA 로그인·스크래핑을 위해 \
                    playwright-server 사이드카가 반드시 실행 중이어야 합니다. \
                    Render 등 playwright-server 없는 환경에서 호출 시 503 오류가 발생합니다."""
    )
    @ApiResponse(responseCode = "204", description = "파이프라인 처리 완료 (응답 바디 없음)")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trigger() {
        useCase.process();
    }

    // 이미지 직접 업로드 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송
    @Operation(
            summary = "이미지 직접 업로드 트리거",
            description = "분석할 이미지를 multipart로 업로드. date 미지정 시 오늘 날짜 사용"
    )
    @ApiResponse(responseCode = "204", description = "파이프라인 처리 완료 (응답 바디 없음)")
    @PostMapping(value = "/orders/from-image", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerFromImage(
            @RequestPart("image") MultipartFile image,
            @Parameter(description = "매매 날짜 (yyyy-MM-dd). 미지정 시 오늘 날짜 사용", example = "2026-06-13")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate tradeDate = date != null ? date : LocalDate.now();
        processImages.process(readBytes(image), tradeDate);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("이미지 파일 읽기 실패: " + file.getOriginalFilename(), e);
        }
    }
}
