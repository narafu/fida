package com.fida.adapter.out.kista;

import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnProperty("kista.url")
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
    public void sendOrders(TradingRecord record) {
        var fidaOrderRequest = FidaOrderRequest.of(record, SYMBOL);

        var targetUrl = UriComponentsBuilder.fromUriString(baseUrl)
                .path(INTERNAL_ORDER_PATH)
                .toUriString();

        var httpEntity = createInternalRequestEntity(fidaOrderRequest);

        restTemplate.postForObject(targetUrl, httpEntity, Void.class);
    }

    /**
     * X-Internal-Token 헤더를 포함한 HttpEntity를 생성합니다.
     */
    private HttpEntity<FidaOrderRequest> createInternalRequestEntity(FidaOrderRequest requestBody) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_INTERNAL_TOKEN_HEADER, internalApiToken);

        return new HttpEntity<>(requestBody, headers);
    }
}