package com.fida.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

// "7개"·"1,234"·"7.0"처럼 OCR이 정수 필드에 섞어 보내는 문자를 허용 — 숫자로 해석 불가하면 null
public class CommaIntegerDeserializer extends JsonDeserializer<Integer> {

    // 금액 필드와 동일한 정제 규칙 재사용
    private final CommaBigDecimalDeserializer delegate = new CommaBigDecimalDeserializer();

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        BigDecimal value = delegate.deserialize(p, ctxt);
        if (value == null) {
            return null;
        }
        // 수량은 정수 — 소수점 이하는 버림
        try {
            return value.setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            // int 범위 초과 등 비정상 값은 null 처리
            return null;
        }
    }
}
