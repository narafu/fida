package com.fida.adapter.out.heartbeat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartbeatAdapter 테스트")
class HeartbeatAdapterTest {

    private static final String HEARTBEAT_URL = "https://hc-ping.com/test-uuid";

    @Mock RestTemplate restTemplate;
    HeartbeatAdapter adapter;

    @Test
    @DisplayName("url이 설정돼 있으면 ping()이 GET 요청을 보낸다")
    void ping_urlSet_sendsGetRequest() {
        adapter = new HeartbeatAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "url", HEARTBEAT_URL);

        adapter.ping();

        verify(restTemplate).getForObject(HEARTBEAT_URL, String.class);
    }

    @Test
    @DisplayName("url이 빈 문자열이면 ping()은 restTemplate과 상호작용하지 않는다")
    void ping_urlBlank_skipsWithoutInteraction() {
        adapter = new HeartbeatAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "url", "");

        adapter.ping();

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("url이 null이면 ping()은 restTemplate과 상호작용하지 않는다")
    void ping_urlNull_skipsWithoutInteraction() {
        adapter = new HeartbeatAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "url", null);

        adapter.ping();

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("restTemplate 호출이 실패해도 ping()은 예외를 삼킨다")
    void ping_httpFailure_swallowedNotThrown() {
        adapter = new HeartbeatAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "url", HEARTBEAT_URL);
        when(restTemplate.getForObject(HEARTBEAT_URL, String.class))
                .thenThrow(new RestClientException("timeout"));

        assertThatCode(() -> adapter.ping()).doesNotThrowAnyException();
    }
}
