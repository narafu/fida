package com.fida.domain.port.in;

import java.time.LocalDate;

public interface ProcessImagesUseCase {
    // 이미지 바이트 배열 + 거래 날짜로 전체 파이프라인 실행
    void process(byte[] image, LocalDate tradeDate);
}
