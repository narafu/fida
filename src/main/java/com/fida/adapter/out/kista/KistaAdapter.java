package com.fida.adapter.out.kista;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.KistaPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
@ConditionalOnProperty("kista.url")
public class KistaAdapter implements KistaPort {

    private static final String SYMBOL = "SOXL";

    private final RestTemplate restTemplate;
    private final String kistaUrl;

    public KistaAdapter(RestTemplate restTemplate,
                        @Value("${kista.url}") String kistaUrl) {
        this.restTemplate = restTemplate;
        this.kistaUrl = kistaUrl;
    }

    @Override
    public void sendOrders(TradingRecord record) {
        var order = record.order();
        sendEach(order.buyOrders(), "BUY");
        sendEach(order.sellOrders(), "SELL");
    }

    private void sendEach(List<OrderItem> items, String direction) {
        for (var item : items) {
            if (item.price() == null) continue;
            var req = new FidaOrderRequest(SYMBOL, direction, parseQty(item.qty()), item.price());
            restTemplate.postForObject(kistaUrl + "/api/orders/fida", req, Void.class);
        }
    }

    private static Integer parseQty(String qty) {
        if (qty == null) return null;
        try {
            return Integer.parseInt(qty.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
