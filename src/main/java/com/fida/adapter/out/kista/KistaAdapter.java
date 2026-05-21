package com.fida.adapter.out.kista;

import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnProperty("kista.url")
@RequiredArgsConstructor
public class KistaAdapter implements KistaPort {

    private static final String SYMBOL = "SOXL";
    private static final String KISTA_URL ="https://kista-api.onrender.com";
    private static final String ORDER_API_PATH = "/api/orders/fida";

    private final RestTemplate restTemplate;

    @Override
    public void sendOrders(TradingRecord record) {
        var fidaOrderRequest = FidaOrderRequest.of(record, SYMBOL);

        var targetUrl = UriComponentsBuilder.fromUriString(KISTA_URL)
                .path(ORDER_API_PATH)
                .toUriString();

        restTemplate.postForObject(targetUrl, fidaOrderRequest, Void.class);
    }
}
