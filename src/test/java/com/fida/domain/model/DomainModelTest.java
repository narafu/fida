package com.fida.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("도메인 모델 테스트")
class DomainModelTest {

    @Test
    @DisplayName("ScrapedPost는 게시글 메타와 이미지 목록을 담는다")
    void scrapedPost_holds_post_meta_and_images() {
        byte[] img = {1, 2, 3};
        var post = new ScrapedPost("제목", LocalDate.of(2024, 1, 15), "https://example.com", List.of(img));

        assertThat(post.postTitle()).isEqualTo("제목");
        assertThat(post.postDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(post.postUrl()).isEqualTo("https://example.com");
        assertThat(post.images()).hasSize(1);
    }

    @Test
    @DisplayName("OrderItem은 price와 qty가 null일 수 있다")
    void orderItem_allows_null_price_and_qty() {
        var withValues = new OrderItem(new BigDecimal("75000"), "100");
        var withNulls = new OrderItem(null, null);
        var withAll = new OrderItem(new BigDecimal("80000"), "ALL");

        assertThat(withValues.price()).isEqualByComparingTo(new BigDecimal("75000"));
        assertThat(withValues.qty()).isEqualTo("100");
        assertThat(withNulls.price()).isNull();
        assertThat(withNulls.qty()).isNull();
        assertThat(withAll.qty()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("ParsedOrder는 매수/매도 목록과 잔고 정보를 담는다")
    void parsedOrder_holds_buy_sell_and_balance() {
        var buy = new OrderItem(new BigDecimal("75000"), "100");
        var sell = new OrderItem(new BigDecimal("80000"), "ALL");
        var order = new ParsedOrder(
                List.of(buy), List.of(sell),
                new BigDecimal("1000000"), new BigDecimal("72000"), 200
        );

        assertThat(order.buyOrders()).hasSize(1);
        assertThat(order.sellOrders()).hasSize(1);
        assertThat(order.cashBalance()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(order.avgPrice()).isEqualByComparingTo(new BigDecimal("72000"));
        assertThat(order.holdings()).isEqualTo(200);
    }

    @Test
    @DisplayName("ParsedOrder holdings가 0이면 avgPrice는 null이어야 한다")
    void parsedOrder_avgPrice_is_null_when_holdings_zero() {
        var order = new ParsedOrder(List.of(), List.of(), null, null, 0);

        assertThat(order.holdings()).isZero();
        assertThat(order.avgPrice()).isNull();
    }

    @Test
    @DisplayName("TradingRecord.of()는 ScrapedPost와 ParsedOrder로부터 생성된다")
    void tradingRecord_created_from_post_and_order() {
        var post = new ScrapedPost("매매표", LocalDate.of(2024, 1, 15), "https://fanding.kr/1", List.of());
        var parsedOrder = new ParsedOrder(List.of(), List.of(), null, null, 0);

        var record = TradingRecord.of(post, parsedOrder);

        assertThat(record.date()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(record.postTitle()).isEqualTo("매매표");
        assertThat(record.postUrl()).isEqualTo("https://fanding.kr/1");
        assertThat(record.order()).isEqualTo(parsedOrder);
    }
}
