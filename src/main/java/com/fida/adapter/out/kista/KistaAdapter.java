package com.fida.adapter.out.kista;

import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@ConditionalOnProperty("kista.url")
public class KistaAdapter implements KistaPort {

    private final RestTemplate restTemplate;
    private final String kistaUrl;

    public KistaAdapter(RestTemplate restTemplate,
                        @Value("${kista.url}") String kistaUrl) {
        this.restTemplate = restTemplate;
        this.kistaUrl = kistaUrl;
    }

    @Override
    public void sendOrders(TradingRecord record) {
        restTemplate.postForObject(kistaUrl + "/api/fida/orders", record, Void.class);
    }
}
