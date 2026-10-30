package com.fida.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommaBigDecimalDeserializer 테스트")
class CommaBigDecimalDeserializerTest {

    // 테스트 대상 필드를 감싸는 래퍼
    record Holder(@JsonDeserialize(using = CommaBigDecimalDeserializer.class) BigDecimal v) {}

    private final ObjectMapper mapper = new ObjectMapper();

    private BigDecimal parse(String jsonValue) throws Exception {
        return mapper.readValue("{\"v\":" + jsonValue + "}", Holder.class).v();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"1,234.56\"|1234.56",
            "12.5|12.5",
            "\"-3.2\"|-3.2",
            "\"$1,000\"|1000"
    })
    @DisplayName("숫자 문자열·숫자 리터럴을 BigDecimal로 변환한다")
    void parsesNumbers(String json, String expected) throws Exception {
        assertThat(parse(json)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"-\"", "\".\"", "\"2026-01-06\"", "null"})
    @DisplayName("공백·부호만·날짜·null은 null로 처리한다")
    void returnsNull(String json) throws Exception {
        assertThat(parse(json)).isNull();
    }

    @Test
    @DisplayName("필드 누락 시 null")
    void missingField() throws Exception {
        assertThat(mapper.readValue("{}", Holder.class).v()).isNull();
    }
}
