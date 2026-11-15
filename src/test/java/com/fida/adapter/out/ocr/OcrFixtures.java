package com.fida.adapter.out.ocr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// src/test/resources/ocr/<name>.json 픽스처(Gemini가 응답 텍스트로 돌려주는 JSON 본문) 로더
final class OcrFixtures {

    private OcrFixtures() {}

    static String load(String name) {
        String path = "/ocr/" + name + ".json";
        try (var in = OcrFixtures.class.getResourceAsStream(path)) {
            // 리소스가 없으면 NPE 대신 경로를 알려 준다
            if (in == null) {
                throw new IllegalStateException("OCR 픽스처를 찾을 수 없음: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
