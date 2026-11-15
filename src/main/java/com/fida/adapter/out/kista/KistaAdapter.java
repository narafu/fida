package com.fida.adapter.out.kista;

import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class KistaAdapter implements KistaPort {

    private static final String SYMBOL = "SOXL";
    private static final String INTERNAL_ORDER_PATH = "/api/internal/fida-orders";
    private static final String X_INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestTemplate restTemplate;

    @Value("${kista.url}")
    private String baseUrl;
    @Value("${kista.internal-token}")
    private String internalApiToken;

    @Override
    public UUID sendOrders(TradingRecord record) {
        var request = FidaOrderRequest.of(record, SYMBOL);

        var targetUrl = baseUrl + INTERNAL_ORDER_PATH;

        var response = restTemplate.postForObject(targetUrl, createInternalRequestEntity(request), KistaOrderResponse.class);
        // 응답은 저장 ID만 사용 — 요청값 에코라 다른 필드 대조는 의미 없음
        if (response == null || response.id() == null) {
            throw new IllegalStateException("KISTA 응답에 id가 없습니다");
        }
        return response.id();
    }

    private HttpEntity<FidaOrderRequest> createInternalRequestEntity(FidaOrderRequest requestBody) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_INTERNAL_TOKEN_HEADER, internalApiToken);

        return new HttpEntity<>(requestBody, headers);
    }
}