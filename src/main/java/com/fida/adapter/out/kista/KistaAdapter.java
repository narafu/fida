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

import java.util.UUID;

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
    public UUID sendOrders(TradingRecord record) {
        var request = FidaOrderRequest.of(record, SYMBOL);

        var targetUrl = UriComponentsBuilder.fromUriString(baseUrl)
                .path(INTERNAL_ORDER_PATH)
                .toUriString();

        var response = restTemplate.postForObject(targetUrl, createInternalRequestEntity(request), KistaOrderResponse.class);
        if (response == null) {
            throw new IllegalStateException("KISTA 응답이 null입니다");
        }
        validate(request, response);
        return response.id();
    }

    private void validate(FidaOrderRequest req, KistaOrderResponse res) {
        if (!req.tradeDate().equals(res.tradeDate())
                || req.holdings() != res.holdings()
                || req.orders().size() != res.orders().size()) {
            throw new IllegalStateException(String.format(
                    "KISTA 응답 불일치 — tradeDate: %s→%s, holdings: %d→%d, orders: %d→%d",
                    req.tradeDate(), res.tradeDate(),
                    req.holdings(), res.holdings(),
                    req.orders().size(), res.orders().size()
            ));
        }
    }

    private HttpEntity<FidaOrderRequest> createInternalRequestEntity(FidaOrderRequest requestBody) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_INTERNAL_TOKEN_HEADER, internalApiToken);

        return new HttpEntity<>(requestBody, headers);
    }
}