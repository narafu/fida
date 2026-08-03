# OCR Sell Rows Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover real sell orders from Gemini's fixed-position `sell_rows` output when its normalized `sell` array is empty, without inventing orders for an actually empty sell table.

**Architecture:** Extend only the OCR boundary DTO and prompt in `GeminiVisionAdapter`. Resolve sell orders by preferring the existing normalized `sell` array and falling back to filtered physical `sell_rows`; downstream domain and KISTA contracts remain unchanged.

**Tech Stack:** Java 21, Spring Boot 3.4, Jackson records, JUnit 5, AssertJ, Mockito, Gradle

## Global Constraints

- Preserve Hexagonal Architecture dependency direction.
- Keep `GeminiOrderResult.sellRows` optional for backward compatibility.
- Never derive a sell order from holdings, close price, average price, or performance values.
- Treat three null `sell_rows` entries as no sell order.
- Add `//` comments for new business-logic blocks; do not add Javadoc or block comments.

---

### Task 1: Recover omitted sell orders from physical sell rows

**Files:**
- Modify: `src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java`
- Modify: `src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java`

**Interfaces:**
- Consumes: Gemini JSON fields `sell: List<RawOrderItem>` and optional `sell_rows: List<RawOrderItem>`.
- Produces: `ParsedOrder.sellOrders(): List<OrderItem>` with existing `sell` preferred and non-empty `sell_rows` used only as fallback.

- [ ] **Step 1: Write the failing recovery test**

Add an adapter test whose Gemini response contains `"sell":[]` and:

```json
"sell_rows":[
  {"price":null,"qty":null},
  {"price":null,"qty":null},
  {"price":112.79,"qty":"ALL"}
]
```

Assert the resulting sell orders contain exactly one `OrderItem` with price `112.79` and quantity `ALL`, and verify `notifyOcrWarning` contains `sell_rows`.

- [ ] **Step 2: Run the recovery test to verify RED**

Run:

```bash
bash gradlew test --tests "com.fida.adapter.out.ocr.GeminiVisionAdapterTest.analyze_recovers_sell_from_sell_rows_when_sell_is_empty"
```

Expected: FAIL because `GeminiOrderResult` ignores `sell_rows` and the result has no sell orders.

- [ ] **Step 3: Add empty-table and precedence tests while still RED**

Add tests asserting:

```java
assertThat(result.sellOrders()).isEmpty();
```

when all three `sell_rows` entries contain nulls, and asserting the existing `sell` order is returned exactly once when both `sell` and `sell_rows` contain values.

- [ ] **Step 4: Implement the minimal prompt, DTO, and resolver changes**

Add `sell_rows` to the requested JSON schema and state that it must contain exactly three physical rows in order, including null placeholders. Extend `GeminiOrderResult` with `List<RawOrderItem> sellRows`.

Replace direct sell conversion with:

```java
private List<OrderItem> resolveSellOrders(GeminiOrderResult raw) {
    List<OrderItem> sellOrders = toOrderItems(raw.sell());
    if (!sellOrders.isEmpty()) {
        return sellOrders;
    }

    // 정규화된 sell이 비었을 때만 물리적 매도 3개 행에서 실제 값이 있는 행을 복구한다.
    List<OrderItem> recovered = toOrderItems(raw.sellRows());
    if (!recovered.isEmpty()) {
        String warning = "Gemini sell 누락을 sell_rows에서 복구: " + recovered;
        log.warn(warning);
        safeNotifyOcrWarning(warning);
    }
    return recovered;
}
```

This filtering preserves an empty result when all three rows are null and avoids duplicates when `sell` is already populated.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run:

```bash
bash gradlew test --tests "com.fida.adapter.out.ocr.GeminiVisionAdapterTest"
```

Expected: all `GeminiVisionAdapterTest` tests pass.

- [ ] **Step 6: Run full verification**

Run:

```bash
bash gradlew test
bash gradlew build
git diff --check
```

Expected: both Gradle commands exit 0 and `git diff --check` prints no errors.

- [ ] **Step 7: Commit the implementation**

```bash
git add src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java \
  src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java
git commit -m "fix: OCR 매도 마지막 행 누락 복구"
```

