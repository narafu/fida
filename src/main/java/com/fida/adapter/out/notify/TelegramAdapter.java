package com.fida.adapter.out.notify;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.NotifyPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class TelegramAdapter implements NotifyPort {

    private final RestTemplate restTemplate;
    private final String botToken;
    private final String chatId;

    public TelegramAdapter(
            RestTemplate restTemplate,
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.chat-id}") String chatId) {
        this.restTemplate = restTemplate;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    @Override
    public void notify(TradingRecord record) {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = Map.of("chat_id", chatId, "text", buildMessage(record));
        try {
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            throw new NotifyException("텔레그램 알림 실패", e);
        }
    }

    String buildMessage(TradingRecord record) {
        var order = record.order();
        return "✅ Fanding 자동 입력 완료\n\n" +
               "📅 " + record.date() + "\n\n" +
               "📈 매수:\n" + formatLines(order.buyOrders()) + "\n\n" +
               "📉 매도:\n" + formatLines(order.sellOrders()) + "\n\n" +
               "💵 현사이클 시작: $" + fmt(order.currentCycleStart()) + "\n" +
               "📊 평단: $" + fmt(order.avgPrice()) + " | 보유: " + order.holdings() + "개";
    }

    private String formatLines(List<OrderItem> items) {
        List<OrderItem> valid = items.stream()
                .filter(i -> i.price() != null)
                .collect(Collectors.toList());
        if (valid.isEmpty()) return "  없음";
        return IntStream.range(0, valid.size())
                .mapToObj(i -> "  " + (i + 1) + ". $" +
                               valid.get(i).price().toPlainString() +
                               " × " + fmtQty(valid.get(i).qty()))
                .collect(Collectors.joining("\n"));
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.toPlainString() : "-";
    }

    private String fmtQty(String qty) {
        if ("ALL".equals(qty)) return "전부";
        return qty != null ? qty : "-";
    }
}
