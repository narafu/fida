package com.fida.domain.port.in;

import java.time.LocalDate;
import java.util.UUID;

public interface ProcessImagesUseCase {
    // 이미지 바이트 배열 + 거래 날짜로 전체 파이프라인 실행, KISTA 저장 ID 반환
    UUID process(byte[] image, LocalDate tradeDate);
}
