package com.fida.adapter.out.notify;

import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private final RestTemplate restTemplate;
    @Value("${telegram.bot-token}")
    private String botToken;
    @Value("${telegram.chat-id}")
    private String chatId;

    @Override
    public void notify(TradingRecord record) {
        sendText(buildMessage(record));
    }

    @Override
    public void notifyKistaSuccess(TradingRecord record, UUID savedId) {
        var order = record.order();
        int totalOrders = order.buyOrders().size() + order.sellOrders().size();
        String idStr = savedId != null ? savedId.toString().substring(0, 8) + "..." : "-";
        sendText("✅ KISTA 저장 완료\n📅 " + record.date() + "\n🆔 " + idStr + "\n🎫 SOXL " + totalOrders + "건");
    }

    @Override
    public void notifyKistaFailure(TradingRecord record, Exception cause) {
        String reason = (cause.getMessage() != null) ? cause.getMessage() : "알 수 없는 오류";
        sendText("❌ KISTA 전송 실패\n📅 " + record.date() + "\n사유: " + reason);
    }

    @Override
    public void notifyGeminiError(Exception cause) {
        String reason = (cause.getMessage() != null) ? cause.getMessage() : "알 수 없는 오류";
        sendText("❌ Gemini API 오류\n사유: " + reason);
    }

    @Override
    public void notifyApplicationFailure(String stage, Exception cause) {
        String reason = (cause.getMessage() != null) ? cause.getMessage() : "알 수 없는 오류";
        sendText("❌ FIDA 실행 실패\n단계: " + stage + "\n사유: " + reason);
    }

    @Override
    public void notifyGeminiQuota(int remaining, int limit) {
        sendText("ℹ️ Gemini 일일한도\n잔여: (" + remaining + "/" + limit + ")");
    }

    private void sendText(String message) {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = Map.of("chat_id", chatId, "text", message);
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
               "💵 현사이클 실현수익: $" + fmt(order.currentCycleRealizedPnl()) + "\n" +
               "📊 평단: $" + fmt(order.avgPrice()) + " | 보유: " + order.holdings() + "개";
    }

    private String formatLines(List<OrderItem> items) {
        List<OrderItem> valid = items.stream()
                .filter(i -> i.price() != null)
                .toList();
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
