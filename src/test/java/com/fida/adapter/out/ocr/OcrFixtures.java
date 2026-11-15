package com.fida.adapter.out.ocr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// src/test/resources/ocr/<name>.json 픽스처(Gemini가 응답 텍스트로 돌려주는 JSON 본문) 로더
final class OcrFixtures {

    private OcrFixtures() {}

    static String load(String name) {
        try (var in = OcrFixtures.class.getResourceAsStream("/ocr/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
