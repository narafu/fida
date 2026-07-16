# OCR Holdings Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a misread `holding_qty`/buy quantity from overriding the image's explicit cumulative holdings.

**Architecture:** Keep the correction inside `GeminiVisionAdapter`. Resolve positive `cumulative_qty` before fallback fields, warn when positive holding candidates disagree, and strengthen the OCR prompt so an absent `보유개수` label produces `null`.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito

## Global Constraints

- Do not change domain models or KISTA/Sheets/Telegram DTO structures.
- Follow TDD: observe the production regression test fail before changing implementation.
- Use `//` comments only; do not add Javadoc or block comments.

---

### Task 1: Correct holdings resolution and expose disagreements

**Files:**
- Modify: `src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java`
- Test: `src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java`

**Interfaces:**
- Consumes: Gemini fields `holding_qty`, `cumulative_qty`, `buy_qty`, and `holdings`.
- Produces: `ParsedOrder.holdings()` and `NotifyPort.notifyOcrWarning(String)` on candidate disagreement.

- [ ] **Step 1: Write the failing production regression test**

Add a Gemini response containing `holding_qty=57`, `cumulative_qty=74`, `buy_qty=57`, and `holdings=74`; assert final holdings is 74 and an OCR warning is emitted.

- [ ] **Step 2: Verify the regression test fails**

Run: `bash gradlew test --tests "com.fida.adapter.out.ocr.GeminiVisionAdapterTest.analyze_prefers_cumulative_qty_and_warns_when_holding_candidates_disagree"`

Expected: FAIL because current resolution returns 57 and sends no warning.

- [ ] **Step 3: Implement the minimal correction**

Resolve positive `cumulative_qty` first. When both `holding_qty` and `cumulative_qty` are positive and unequal, call `safeNotifyOcrWarning` with both values and state that cumulative quantity was selected. Strengthen the prompt so `holding_qty` must be null unless the exact `보유개수` label exists.

- [ ] **Step 4: Verify focused and full tests**

Run the focused test, then `bash gradlew test`, then `bash gradlew build`.

Expected: all commands exit 0.

- [ ] **Step 5: Review the diff**

Run: `git diff --check && git diff -- src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java`

Expected: no whitespace errors; changes remain limited to prompt, resolution/warning behavior, and regression coverage.
