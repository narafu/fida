package com.fida.domain.port.in;

import com.fida.domain.model.KistaResult;

import java.time.LocalDate;

public interface ProcessImagesUseCase {
    // 이미지 바이트 배열 + 거래 날짜로 전체 파이프라인 실행, KISTA 응답 반환
    KistaResult process(byte[] image, LocalDate tradeDate);
}
