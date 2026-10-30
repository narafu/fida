package com.fida.adapter.out.notify;

import com.fida.domain.exception.NotifyException;
import com.fida.domain.model.OrderItem;
import com.fida.domain.model.TradingRecord;
import com.fida.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
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
        sendQuietly("✅ KISTA 저장 완료\n📅 " + record.date() + "\n🆔 " + idStr + "\n🎫 SOXL " + totalOrders + "건");
    }

    @Override
    public void notifyKistaFailure(TradingRecord record, Exception cause) {
        String reason = summarizeReason(cause);
        sendQuietly("❌ KISTA 전송 실패\n📅 " + record.date() + "\n사유: " + reason);
    }

    @Override
    public void notifyGeminiError(Exception cause) {
        String reason = summarizeReason(cause);
        sendQuietly("❌ Gemini API 오류\n사유: " + reason);
    }

    @Override
    public void notifyApplicationFailure(String stage, Exception cause) {
        String reason = summarizeReason(cause);
        sendQuietly("❌ FIDA 실행 실패\n단계: " + stage + "\n사유: " + reason);
    }

    @Override
    public void notifyOcrWarning(String warning) {
        sendQuietly("⚠️ OCR 검증 경고\n" + warning);
    }

    @Override
    public void notifyGeminiQuota(int remaining, int limit) {
        sendQuietly("ℹ️ Gemini 일일한도\n잔여: (" + remaining + "/" + limit + ")");
    }

    // 부가 알림은 best-effort — 실패는 (마스킹된) 경고 로그만 남기고 삼킴
    private void sendQuietly(String message) {
        try {
            sendText(message);
        } catch (NotifyException e) {
            log.warn("{}", e.getMessage());
        }
    }

    private void sendText(String message) {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = Map.of("chat_id", chatId, "text", message);
        try {
            restTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            // 요청 URL에 봇 토큰이 포함됨 — 메시지 마스킹 + 원인 체인 제거로 로그·응답 유출 차단
            String reason = String.valueOf(e.getMessage());
            if (botToken != null && !botToken.isEmpty()) {
                reason = reason.replace(botToken, "***");
            }
            throw new NotifyException("텔레그램 알림 실패: " + e.getClass().getSimpleName() + " " + reason, null);
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
                               " × " + fmtQty(valid.get(i)))
                .collect(Collectors.joining("\n"));
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.toPlainString() : "-";
    }

    private String fmtQty(OrderItem item) {
        if (item.isAll()) return "전부";
        return item.qty() != null ? item.qty() : "-";
    }

    private String summarizeReason(Exception cause) {
        String message = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "알 수 없는 오류";
        if (message.contains("Failed to launch the browser process")) {
            return "브라우저 실행 실패 (Chromium/Puppeteer)";
        }
        if (message.contains("Too Many Requests") || message.contains("RESOURCE_EXHAUSTED") || message.contains("quota")) {
            return "Gemini 일일한도 초과";
        }
        if (message.contains("로그인 실패")) {
            return "Fanding 로그인 실패";
        }
        if (message.contains("이미지를 찾을 수 없습니다")) {
            return "Fanding 게시글 이미지 없음";
        }
        if (message.contains("playwright-server 호출 실패")) {
            return "Fanding 스크래퍼 실패";
        }
        return message.length() > 180 ? message.substring(0, 180) + "..." : message;
    }
}
