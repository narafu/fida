package com.fida.domain.port.in;

public interface ProcessUrlUseCase {
    // fanding 상세 페이지 URL로 전체 파이프라인 실행
    void process(String postUrl);
}
