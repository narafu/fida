# FIDA 건강검진 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 신뢰성 강화 + 저렴한 모델 인계 대비 — 전체 프로젝트 검토에서 발견된 취약점·문서 부패·안전망 누락을 영향력 순으로 수정한다.

**Architecture:** 기존 Hexagonal Architecture를 유지한 채 국소 수정만 수행. 새 컴포넌트 없음. playwright-server 사이드카(Node.js), Spring 어댑터 2곳, 예외 핸들러, GitHub Actions 워크플로, 문서 4종을 다룬다.

**Tech Stack:** Java 21 / Spring Boot 3.4.4 / JUnit 5 + AssertJ + MockRestServiceServer / Node.js 20 (puppeteer)

## Global Constraints

- 커밋 author 확인: `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`
- **git push 금지** — 사용자가 명시적으로 요청할 때만
- 주석은 `//` 인라인만 (Javadoc·블록 주석 금지), 신규 코드에 역할 주석 필수
- 테스트 실행 명령: `bash gradlew test` (전체) / `bash gradlew test --tests "<FQCN>"` (단건)
- `@Value` 설정 필드는 non-final (CI Lombok 이슈 — 루트 CLAUDE.md 참조)
- Gemini 모델 `gemini-2.5-flash-lite`, Spring 스케줄 cron `0 0 7 * * TUE-SAT` 변경 금지
- playwright-server는 Node.js 유지 (Java 이식 금지)
- 서브에이전트가 Java 파일 수정 시 BOM 삽입 주의 — 커밋 전 `grep -rl $'\xef\xbb\xbf' src --include="*.java"` 확인

---

### Task 1: playwright-server 스크래핑 중 /health 블로킹 해소 [영향력 1위 — 운영 사고 직접 원인]

**문제:** `scrape-server.js`가 `execFileSync`로 자식 프로세스를 동기 실행해 이벤트 루프가 최대 120초 블로킹된다. Render가 `/health`를 헬스체크하므로 스크래핑 도중 헬스체크 연속 실패 → 스크래핑 중간 컨테이너 재시작 위험.

**Files:**
- Modify: `playwright-server/scrape-server.js` (전면 재작성)

**Interfaces:**
- Produces: 기존과 동일한 HTTP API (`GET /scrape`, `GET /scrape-url?url=`, `GET /health`) 및 동일한 응답 JSON 스키마. 변경점: 스크래핑 진행 중 새 스크래핑 요청은 `503 {"success":false,"error":"scrape already in progress"}` (기존 동기 직렬화 의미 보존 — 동시 Chromium 2개 기동으로 인한 free tier OOM 방지)

- [ ] **Step 1: scrape-server.js를 비동기 execFile로 재작성**

전체 파일을 아래 내용으로 교체:

```js
#!/usr/bin/env node
/**
 * scrape-server.js
 * scrape-fanding.js를 HTTP 서버로 감싸서 FIDA(Java)에서 호출할 수 있게 함.
 *
 * 실행: FANDING_EMAIL=... FANDING_PASSWORD=... node scrape-server.js
 * 포트: 3000
 * 엔드포인트: GET /scrape, GET /scrape-url?url=, GET /health
 */

const http = require('http');
const { execFile } = require('child_process');
const path = require('path');

const PORT = process.env.PORT || 3000;
const SCRIPT = path.join(__dirname, 'scrape-fanding.js');

// 스크래핑 동시 실행 방지 플래그 — Chromium 2개 동시 기동 시 free tier 메모리 초과 위험
let scraping = false;

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  if (req.method === 'GET' && req.url === '/scrape') {
    runScraper(res, {}, '/scrape');
    return;
  }

  if (req.method === 'GET' && req.url.startsWith('/scrape-url')) {
    const targetUrl = new URL(req.url, 'http://localhost').searchParams.get('url');
    if (!targetUrl) {
      respondJson(res, 400, { success: false, error: 'url 파라미터 필요' });
      return;
    }
    // SSRF 방지: fanding.kr의 https URL만 허용
    try {
      const u = new URL(targetUrl);
      const host = u.hostname.toLowerCase().replace(/\.$/, '');
      if (u.protocol !== 'https:' || host !== 'fanding.kr') {
        respondJson(res, 400, { success: false, error: 'host not allowed: fanding.kr only' });
        return;
      }
    } catch (_) {
      respondJson(res, 400, { success: false, error: 'invalid url format' });
      return;
    }
    runScraper(res, { TARGET_URL: targetUrl }, `/scrape-url (${targetUrl})`);
    return;
  }

  res.writeHead(404);
  res.end();
});

// 자식 프로세스를 비동기로 실행 — 이벤트 루프를 막지 않아 스크래핑 중에도 /health 응답 가능
function runScraper(res, extraEnv, label) {
  if (scraping) {
    console.warn(`[${new Date().toISOString()}] ${label} 거부 — 스크래핑 이미 진행 중`);
    respondJson(res, 503, { success: false, error: 'scrape already in progress' });
    return;
  }
  scraping = true;
  const start = Date.now();
  console.log(`[${new Date().toISOString()}] ${label} 요청 수신`);

  execFile('node', [SCRIPT], {
    env: { ...process.env, ...extraEnv },
    maxBuffer: 100 * 1024 * 1024,
    timeout: 120000,
  }, (error, stdout, stderr) => {
    scraping = false;
    const elapsed = ((Date.now() - start) / 1000).toFixed(1);
    if (error) {
      const details = {
        error: error.message,
        status: error.code ?? null,
        signal: error.signal ?? null,
        stdout: tail(stdout),
        stderr: tail(stderr),
      };
      console.error(`[${new Date().toISOString()}] ${label} 실패 (${elapsed}s):`, JSON.stringify(details));
      respondJson(res, 500, { success: false, ...details });
      return;
    }
    console.log(`[${new Date().toISOString()}] ${label} 완료 (${elapsed}s)`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(stdout.toString());
  });
}

function respondJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function tail(value) {
  if (!value) return '';
  const text = Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
  return text.slice(-4000);
}

server.listen(PORT, () => {
  console.log(`Scrape server 시작: http://localhost:${PORT}`);
});
```

주의: 기존 `buildChildError()`는 `error.status`를 사용했으나 비동기 `execFile`의 exit code는 `error.code`에 담긴다. 위 코드처럼 `error.code ?? null`을 `status` 필드로 노출해 응답 스키마를 유지할 것 (`FandingScraperAdapterTest`가 500 응답의 `stdout`/`stderr` 필드 포함 여부를 검증함).

- [ ] **Step 2: 문법 검증**

Run: `node --check playwright-server/scrape-server.js`
Expected: 출력 없음 (exit 0)

- [ ] **Step 3: 수동 동작 검증 — 스크래핑 중 /health 응답 확인**

```bash
cd playwright-server
FANDING_EMAIL=dummy@example.com FANDING_PASSWORD=dummy node scrape-server.js &
SERVER_PID=$!
sleep 1
curl -s -m 5 http://localhost:3000/scrape > /dev/null &   # 스크래핑 시작 (더미 자격증명 → 브라우저 기동 후 실패, 수 초 소요)
sleep 1
time curl -s -m 3 http://localhost:3000/health             # 스크래핑 진행 중에 호출
kill $SERVER_PID
```

Expected: `/health`가 1초 이내에 `{"status":"ok"}` 반환 (기존 코드는 스크래핑이 끝날 때까지 응답 없음). 로컬에 puppeteer 미설치 시 `npm ci` 선행 또는 이 단계는 Docker Compose로 대체: `docker compose build playwright-server && docker compose up -d playwright-server` 후 동일 curl 검증.

- [ ] **Step 4: Commit**

```bash
git add playwright-server/scrape-server.js
git commit -m "fix. playwright-server 스크래핑 중 /health 블로킹 해소 — execFileSync를 비동기 execFile로 전환"
```

---

### Task 2: 스크래퍼 제목 날짜 연도 경계 버그 수정 [영향력 2위 — 잘못된 날짜 전송]

**문제:** `FandingScraperAdapter.resolveDateFromTitle()`이 제목의 "M/D"를 무조건 올해로 해석. 1월 초에 전년도 12월 게시글을 처리하면 약 1년 미래 날짜가 시트·KISTA로 전송된다.

**Files:**
- Modify: `src/main/java/com/fida/adapter/out/scraper/FandingScraperAdapter.java:55,67-78`
- Test: `src/test/java/com/fida/adapter/out/scraper/FandingScraperAdapterTest.java`

**Interfaces:**
- Produces: 패키지 전용 메서드 `LocalDate resolveDateFromTitle(String title, LocalDate today)` — 후보 날짜가 `today + 6개월` 초과 미래면 전년도로 해석

- [ ] **Step 1: 실패하는 테스트 작성**

`FandingScraperAdapterTest.java` 끝(마지막 테스트 뒤)에 추가:

```java
    @Test
    @DisplayName("연초에 전년도 12월 게시글 제목을 처리하면 전년도 날짜로 해석한다")
    void resolveDateFromTitle_interprets_far_future_as_previous_year() {
        // 2027-01-01에 "12/31" 제목 처리 → 2027-12-31이 아닌 2026-12-31
        var result = adapter.resolveDateFromTitle("Privacy 12/31 _ SOXL 매매기록", LocalDate.of(2027, 1, 1));

        assertThat(result).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("제목 날짜가 오늘로부터 6개월 이내면 올해 날짜로 해석한다")
    void resolveDateFromTitle_keeps_current_year_for_near_dates() {
        var result = adapter.resolveDateFromTitle("Privacy 7/11 _ SOXL 매매기록", LocalDate.of(2026, 7, 10));

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 11));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `bash gradlew test --tests "com.fida.adapter.out.scraper.FandingScraperAdapterTest"`
Expected: 컴파일 실패 — `resolveDateFromTitle(String, LocalDate)` 메서드 없음

- [ ] **Step 3: 구현**

`FandingScraperAdapter.java`의 기존 `resolveDateFromTitle(String title)` 메서드를 아래 두 메서드로 교체 (기존 메서드는 private였음 — 오버로드는 테스트 접근을 위해 패키지 전용):

```java
    private LocalDate resolveDateFromTitle(String title) {
        return resolveDateFromTitle(title, LocalDate.now());
    }

    LocalDate resolveDateFromTitle(String title, LocalDate today) {
        if (title == null) return null;
        var m = TITLE_DATE_PATTERN.matcher(title);
        if (!m.find()) return null;
        try {
            LocalDate candidate = LocalDate.of(today.getYear(),
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            // 연초에 전년도 연말 게시글을 처리하는 경우: 6개월 초과 미래 날짜는 전년도로 해석
            if (candidate.isAfter(today.plusMonths(6))) {
                candidate = candidate.minusYears(1);
            }
            return candidate;
        } catch (DateTimeException e) {
            return null;
        }
    }
```

`toScrapedPost()`의 호출부(`resolveDateFromTitle(response.postTitle())`)는 그대로 유지된다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `bash gradlew test --tests "com.fida.adapter.out.scraper.FandingScraperAdapterTest"`
Expected: 전체 PASS (기존 7개 + 신규 2개)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fida/adapter/out/scraper/FandingScraperAdapter.java src/test/java/com/fida/adapter/out/scraper/FandingScraperAdapterTest.java
git commit -m "fix. 제목 날짜 연도 경계 버그 — 연초에 전년도 12월 게시글이 미래 날짜로 해석되던 문제 수정"
```

---

### Task 3: OCR 교차검증 경고를 텔레그램 알림으로 승격 [영향력 3위 — 조용한 데이터 오염 감지]

**문제:** `current_cycle_start`=`season_start_capital` 혼동 감지가 `log.warn`뿐. 스케줄 실행에서는 아무도 로그를 안 보므로, 잘못된 값이 검증을 통과해 KISTA로 조용히 전송될 수 있다 (2026-07-10 운영 사례: 10000.00 오파싱). holdings=0+SELL 케이스는 이미 `FidaOrderRequest` 검증 → KISTA 실패 알림으로 표면화되므로 제외.

**Files:**
- Modify: `src/main/java/com/fida/domain/port/out/NotifyPort.java`
- Modify: `src/main/java/com/fida/adapter/out/notify/TelegramAdapter.java`
- Modify: `src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java:218-222`
- Test: `src/test/java/com/fida/domain/port/PortContractTest.java`, `src/test/java/com/fida/adapter/out/notify/TelegramAdapterTest.java`, `src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java`

**Interfaces:**
- Produces: `NotifyPort.notifyOcrWarning(String warning)` — OCR 파싱 결과 의심 시 경고 알림. `TelegramAdapter`가 `⚠️ OCR 검증 경고` 프리픽스로 전송

- [ ] **Step 1: 실패하는 테스트 작성 (3개 파일)**

`PortContractTest.java`의 `notifyPort_has_notifyGeminiQuota_method` 테스트 뒤에 추가:

```java
    @Test
    @DisplayName("NotifyPort는 OCR 검증 경고 알림 메서드를 가진다")
    void notifyPort_has_notifyOcrWarning_method() throws NoSuchMethodException {
        var method = NotifyPort.class.getMethod("notifyOcrWarning", String.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }
```

`TelegramAdapterTest.java` 끝에 추가:

```java
    @Test
    @DisplayName("OCR 검증 경고 메시지를 전송한다")
    void notifyOcrWarning_sends_warning_message() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("OCR 검증 경고")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        adapter.notifyOcrWarning("current_cycle_start 혼동 의심");

        mockServer.verify();
    }
```

`GeminiVisionAdapterTest.java`의 기존 테스트 `analyze_warns_when_current_cycle_start_matches_season_start_capital` 마지막에 검증 한 줄 추가 (import `org.mockito.ArgumentMatchers.anyString` 필요 시 추가):

```java
        // 경고가 로그뿐 아니라 텔레그램 알림으로도 전송되는지 검증
        verify(notifyPort).notifyOcrWarning(org.mockito.ArgumentMatchers.anyString());
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `bash gradlew test --tests "com.fida.domain.port.PortContractTest" --tests "com.fida.adapter.out.notify.TelegramAdapterTest" --tests "com.fida.adapter.out.ocr.GeminiVisionAdapterTest"`
Expected: 컴파일 실패 — `notifyOcrWarning` 메서드 없음

- [ ] **Step 3: 구현 (3개 파일)**

`NotifyPort.java`의 `notifyGeminiQuota` 선언 앞에 추가:

```java
    // OCR 파싱 결과 의심 시 경고 알림 (전송은 차단하지 않음)
    void notifyOcrWarning(String warning);
```

`TelegramAdapter.java`의 `notifyGeminiQuota` 메서드 앞에 추가:

```java
    @Override
    public void notifyOcrWarning(String warning) {
        sendText("⚠️ OCR 검증 경고\n" + warning);
    }
```

`GeminiVisionAdapter.java`의 혼동 감지 블록(218-222행)을 아래로 교체:

```java
            // "현사이클 시작"과 "시즌 시작원금" 행을 혼동한 운영 사례 재발 감지 — 로그 + 텔레그램 경고
            if (raw.currentCycleStart() != null && raw.seasonStartCapital() != null
                    && raw.currentCycleStart().compareTo(raw.seasonStartCapital()) == 0) {
                String warning = "current_cycle_start가 season_start_capital과 동일함(" + raw.currentCycleStart()
                        + ") — \"현사이클 시작\"/\"시즌 시작원금\" 혼동 파싱 가능성, 시트·KISTA 값 확인 필요";
                log.warn(warning);
                safeNotifyOcrWarning(warning);
            }
```

그리고 `safeNotifyGeminiError` 메서드 아래에 헬퍼 추가:

```java
    private void safeNotifyOcrWarning(String warning) {
        try {
            notifyPort.notifyOcrWarning(warning);
        } catch (Exception e) {
            log.warn("OCR 경고 알림 실패: {}", e.getMessage());
        }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `bash gradlew test --tests "com.fida.domain.port.PortContractTest" --tests "com.fida.adapter.out.notify.TelegramAdapterTest" --tests "com.fida.adapter.out.ocr.GeminiVisionAdapterTest"`
Expected: 전체 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fida/domain/port/out/NotifyPort.java src/main/java/com/fida/adapter/out/notify/TelegramAdapter.java src/main/java/com/fida/adapter/out/ocr/GeminiVisionAdapter.java src/test/java/com/fida/domain/port/PortContractTest.java src/test/java/com/fida/adapter/out/notify/TelegramAdapterTest.java src/test/java/com/fida/adapter/out/ocr/GeminiVisionAdapterTest.java
git commit -m "feat. OCR 교차검증 경고를 텔레그램 알림으로 승격 — 혼동 파싱 조용한 전파 방지"
```

---

### Task 4: CI 테스트 워크플로 추가 [영향력 4위 — 인계 후 안전망]

**문제:** `.github/workflows/`에 스케줄 실행 워크플로만 있고 push/PR 시 테스트를 돌리는 CI가 없다. 저렴한 모델이 깨진 코드를 커밋해도 경고가 없다.

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: main push 및 PR 시 `gradlew build` (컴파일+전체 테스트+ArchUnit) 자동 실행

- [ ] **Step 1: 워크플로 파일 생성**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      # 컴파일 + 전체 테스트(JUnit 5 병렬) + ArchUnit 아키텍처 검증 + JAR
      - run: ./gradlew build

      - name: 테스트 리포트 업로드 (실패 시 확인용)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: build/reports/tests/test/
```

- [ ] **Step 2: 로컬에서 동일 명령 검증**

Run: `bash gradlew build`
Expected: BUILD SUCCESSFUL (CI가 실행할 명령과 동일 — 워크플로 자체는 push 후 `gh run list --repo narafu/fida --limit 3`으로 확인하되, **push는 사용자 요청 시에만**)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci. push/PR 시 gradlew build 자동 실행 워크플로 추가"
```

---

### Task 5: GlobalExceptionHandler 사각지대 보강 [영향력 5위]

**문제:** `IllegalStateException`(KISTA 응답 null·응답 불일치·이미지 읽기 실패)과 `OcrException`이 핸들러 없이 기본 500으로 떨어지고 텔레그램 알림도 누락된다.

**Files:**
- Modify: `src/main/java/com/fida/adapter/in/web/GlobalExceptionHandler.java`
- Create: `src/test/java/com/fida/adapter/in/web/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `handleIllegalState(IllegalStateException)` → 500 + `notifyApplicationFailure("HTTP internal state", ...)` / `handleOcr(OcrException)` → 503 (알림 없음 — `GeminiVisionAdapter`가 이미 `notifyGeminiError`로 전송함, 중복 방지)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/fida/adapter/in/web/GlobalExceptionHandlerTest.java` 생성:

```java
package com.fida.adapter.in.web;

import com.fida.adapter.out.ocr.OcrException;
import com.fida.domain.port.out.NotifyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("GlobalExceptionHandler 테스트")
class GlobalExceptionHandlerTest {

    private final NotifyPort notifyPort = mock(NotifyPort.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(notifyPort);

    @Test
    @DisplayName("IllegalStateException은 500으로 변환하고 텔레그램 알림을 전송한다")
    void illegalState_returns_500_and_notifies() {
        var detail = handler.handleIllegalState(new IllegalStateException("KISTA 응답이 null입니다"));

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(detail.getDetail()).contains("KISTA 응답이 null입니다");
        verify(notifyPort).notifyApplicationFailure(eq("HTTP internal state"), any(IllegalStateException.class));
    }

    @Test
    @DisplayName("OcrException은 503으로 변환하고 중복 알림은 보내지 않는다")
    void ocrException_returns_503_without_duplicate_notify() {
        var detail = handler.handleOcr(new OcrException("Gemini JSON 파싱 실패"));

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        verifyNoInteractions(notifyPort);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `bash gradlew test --tests "com.fida.adapter.in.web.GlobalExceptionHandlerTest"`
Expected: 컴파일 실패 — `handleIllegalState`/`handleOcr` 메서드 없음

- [ ] **Step 3: 구현**

`GlobalExceptionHandler.java`의 `handleIllegalArgument` 메서드 아래에 추가 (import `com.fida.adapter.out.ocr.OcrException` 추가 — 인바운드→아웃바운드 어댑터 예외 참조는 기존 `ScraperException` 선례와 동일):

```java
    // KISTA 응답 불일치·null 응답 등 내부 상태 오류 — 500 + 텔레그램 알림
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.error("IllegalStateException: {}", ex.getMessage(), ex);
        safeNotify("HTTP internal state", ex);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        detail.setTitle("Internal State Error");
        return detail;
    }

    // OCR 실패 — GeminiVisionAdapter가 이미 notifyGeminiError로 알림 전송하므로 상태 변환만 수행
    @ExceptionHandler(OcrException.class)
    public ProblemDetail handleOcr(OcrException ex) {
        log.warn("OcrException: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        detail.setTitle("OCR Failed");
        return detail;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `bash gradlew test --tests "com.fida.adapter.in.web.GlobalExceptionHandlerTest"`
Expected: PASS. 이어서 `bash gradlew test --tests "com.fida.architecture.HexagonalArchitectureTest"`로 아키텍처 규칙 위반 없음 확인 (ScraperException 선례가 있어 통과 예상)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fida/adapter/in/web/GlobalExceptionHandler.java src/test/java/com/fida/adapter/in/web/GlobalExceptionHandlerTest.java
git commit -m "feat. IllegalStateException·OcrException 전역 핸들러 추가 — 500 사각지대 및 알림 누락 해소"
```

---

### Task 6: 문서 정합화 [영향력 6위 — 인계 지도의 거짓 정보 제거]

**문제:** 확인된 문서-코드 불일치 4건 + 미문서화 환경변수 1건. 저렴한 모델이 CLAUDE.md를 지도로 삼으므로 거짓 정보는 잘못된 수정으로 직결된다.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `AGENTS.md` (전면 교체 — 포인터화)
- Modify: `src/main/java/com/fida/adapter/CLAUDE.md`
- Modify: `playwright-server/CLAUDE.md`

- [ ] **Step 1: CLAUDE.md 수정 (4곳)**

(a) Commands 섹션의 아래 줄 삭제 (from-url 엔드포인트는 커밋 802c661에서 제거됨):
```
curl -X POST http://localhost:7070/api/fida/orders/from-url -H "Content-Type: application/json" -d '{"postUrl":"https://fanding.kr/..."}'
```

(b) Current Status 섹션의 GH Actions 크론 표기 수정. 기존:
```
- **GitHub Actions Cron 전환 완료 및 검증 완료** — `.github/workflows/fida-schedule.yml` (UTC `0 22 * * 1-5` = KST 화~토 07:00)
```
→ 변경:
```
- **GitHub Actions Cron 전환 완료 및 검증 완료** — `.github/workflows/fida-schedule.yml` (UTC `0 21 * * 1-5` = KST 화~토 06:00; Render 스케줄러 07:00과 1시간 시차 이중화)
```

(c) Environment Variables 표에 행 추가 (`INTERNAL_API_TOKEN` 행 아래):
```
| `GEMINI_QUOTA_USAGE_PATH` | Gemini 일일 사용량 파일 경로 (기본 `/tmp/fida-gemini-quota-usage.json`) — GH Actions는 `/state/...`로 지정해 cache로 영속화 |
```

(d) Commands 섹션 from-image curl 예시가 없다면 from-url 삭제 자리에 추가:
```
curl -X POST http://localhost:7070/api/fida/orders/from-image -F "image=@/path/to/screenshot.png" -F "date=2026-07-10"
```

- [ ] **Step 2: AGENTS.md를 포인터로 일원화**

161줄 복사본을 전면 교체 (사용자 선호: 두 방식 병용 금지, 일원화):

```markdown
# AGENTS.md

이 프로젝트의 에이전트 가이드는 **CLAUDE.md 단일 문서**로 관리한다. 아래 문서를 그대로 따를 것.

- 루트 가이드: [CLAUDE.md](CLAUDE.md)
- 어댑터 규칙: `src/main/java/com/fida/adapter/CLAUDE.md`
- 도메인 제약: `src/main/java/com/fida/domain/CLAUDE.md`
- playwright-server 특이사항: `playwright-server/CLAUDE.md`
```

- [ ] **Step 3: adapter/CLAUDE.md 인바운드 웹 레이어 수정**

기존:
```
- `FidaOrderController`: `/api/fida/orders` 3개 엔드포인트 — 모두 204 반환, 응답 바디 없음
```
→ 변경:
```
- `FidaOrderController`: 2개 엔드포인트 — `POST /orders`(204, 바디 없음), `POST /orders/from-image`(200, KISTA 저장 ID 포함 `FromImageResponse` 반환)
```

- [ ] **Step 4: playwright-server/CLAUDE.md API 응답 스키마 수정**

기존:
```
- `GET /scrape` — fanding.kr 최신 게시물 스크래핑. 응답: `{ postDate, title, imageUrl }`
```
→ 변경:
```
- `GET /scrape` — fanding.kr 최신 게시물 스크래핑. 응답: `{ success, postTitle, postDate, postUrl, images: [{ base64, mimeType }] }` / 실패: `{ success: false, error, status, signal, stdout, stderr }`
- 스크래핑 진행 중 새 스크래핑 요청은 503 `{ success: false, error: "scrape already in progress" }` (동시 실행 방지)
```

- [ ] **Step 5: 검증 및 Commit**

Run: `grep -rn "from-url" CLAUDE.md AGENTS.md src/ && echo "STALE FOUND" || echo "CLEAN"`
Expected: `CLEAN`

```bash
git add CLAUDE.md AGENTS.md src/main/java/com/fida/adapter/CLAUDE.md playwright-server/CLAUDE.md
git commit -m "docs. 문서-코드 불일치 정합화 — from-url 제거 반영, 크론 06:00 정정, AGENTS.md 포인터 일원화"
```

---

### Task 7: KistaPort 일원화 및 소소한 정리 [영향력 7위]

**문제:** (a) `application.yml`의 `kista.url`에 기본값이 있어 `@ConditionalOnProperty("kista.url")`이 항상 참 → `Optional<KistaPort>`는 실제로 절대 비지 않는 죽은 분기 (사용자 선호: 일원화). (b) `application-test.yml`의 죽은 키 `scraper.url` (실제 키는 `fanding.scraper.url`). (c) `settings.gradle.kts`의 멀티모듈 주석 보일러플레이트.

**Files:**
- Modify: `src/main/java/com/fida/adapter/out/kista/KistaAdapter.java` (`@ConditionalOnProperty` 제거)
- Modify: `src/main/java/com/fida/application/service/TradingRecordService.java` (Optional 제거 → `@RequiredArgsConstructor` 불가한 기존 명시적 생성자 유지)
- Modify: `src/test/java/com/fida/application/service/TradingRecordServiceTest.java`
- Modify: `src/test/resources/application-test.yml`, `settings.gradle.kts`

**Interfaces:**
- Produces: `TradingRecordService(ScraperPort, OcrPort, SheetPort, NotifyPort, KistaPort)` — KistaPort 필수 주입

- [ ] **Step 1: 테스트 수정 (기대 동작 먼저 고정)**

`TradingRecordServiceTest.java`에서:
- 모든 `Optional.of(kistaPort)` → `kistaPort`로 교체, `import java.util.Optional;` 제거
- 죽은 분기 테스트 2개 삭제: `process_skips_kista_when_absent` ("KistaPort가 없으면 KISTA 호출을 건너뛴다"), `processImages_throws_when_kista_absent` ("process(images, date)는 KISTA 없을 때 IllegalStateException을 던진다")
- `process_executes_full_pipeline_in_order`, `process_passes_correct_tradingRecord_to_sheet_and_notify`의 `Optional.empty()` → `kistaPort` (해당 테스트는 kista 반환값 불필요 — `when(kistaPort.sendOrders(any())).thenReturn(sampleKistaResult)` 추가)

- [ ] **Step 2: 테스트 실패(컴파일 오류) 확인**

Run: `bash gradlew test --tests "com.fida.application.service.TradingRecordServiceTest"`
Expected: 컴파일 실패 — 생성자 시그니처 불일치

- [ ] **Step 3: 구현**

`KistaAdapter.java`: `@ConditionalOnProperty("kista.url")` 어노테이션과 해당 import 삭제.

`TradingRecordService.java`: 필드·생성자·사용부에서 Optional 제거:

```java
    private final ScraperPort scraper;
    private final OcrPort ocr;
    private final SheetPort sheet;
    private final NotifyPort notify;
    private final KistaPort kista;

    public TradingRecordService(ScraperPort scraper, OcrPort ocr, @Lazy SheetPort sheet,
                                NotifyPort notify, KistaPort kista) {
        this.scraper = scraper;
        this.ocr = ocr;
        this.sheet = sheet;
        this.notify = notify;
        this.kista = kista;
    }
```

`process(byte[], LocalDate)`에서 `var k = kista.orElseThrow(...)` 줄 삭제 후 `kista.sendOrders(record)` 직접 호출. `processPost()`의 `kista.ifPresent(k -> { ... })` 블록을 try/catch만 남기고 평탄화:

```java
        // KISTA 실패는 sheet/notify에 영향 없음 — 실패 알림만 전송
        try {
            var result = kista.sendOrders(record);
            safeNotify(() -> notify.notifyKistaSuccess(record, result.id()));
        } catch (Exception e) {
            log.warn("KISTA 전송 실패 (무시): {}", e.getMessage());
            safeNotify(() -> notify.notifyKistaFailure(record, e));
        }
```

`import java.util.Optional;` 제거.

- [ ] **Step 4: 소소한 정리**

`src/test/resources/application-test.yml`: 죽은 키 블록 삭제 (실제 키 `fanding.scraper.url`은 `application.yml` 기본값으로 해결됨):
```yaml
scraper:
  url: http://playwright-server:3000/scrape
```

`settings.gradle.kts`: `rootProject.name = "fida"` 한 줄만 남기고 주석 보일러플레이트 삭제.

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `bash gradlew build`
Expected: BUILD SUCCESSFUL — 전체 테스트 + ArchUnit 통과

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fida/adapter/out/kista/KistaAdapter.java src/main/java/com/fida/application/service/TradingRecordService.java src/test/java/com/fida/application/service/TradingRecordServiceTest.java src/test/resources/application-test.yml settings.gradle.kts
git commit -m "refactor. KistaPort 일원화 — 항상 참인 @ConditionalOnProperty·Optional 죽은 분기 제거 + 설정 잔재 정리"
```

주의: `adapter/CLAUDE.md`의 "옵셔널 아웃바운드 어댑터" File Interaction Rule은 미래의 진짜 옵셔널 어댑터용 일반 규칙이므로 유지. 루트 CLAUDE.md의 `KISTA_URL` 표 행도 유효(기본값 오버라이드 용도)하므로 유지.

---

## 최종 검증

- [ ] `bash gradlew build` — 전체 테스트 + ArchUnit + JAR 성공
- [ ] `grep -rl $'\xef\xbb\xbf' src --include="*.java"` — BOM 없음 (출력 없어야 함)
- [ ] `node --check playwright-server/scrape-server.js` — 문법 통과
- [ ] `docker compose up --build` 후 `curl -X POST http://localhost:7070/api/fida/orders/from-image -F "image=@<테스트 이미지>"` 로 엔드투엔드 확인 (선택 — 실제 KISTA/텔레그램 호출 발생하므로 사용자 판단)
- [ ] push는 사용자 명시 요청 시에만 — push 후 `gh run list --repo narafu/fida --limit 3`으로 신규 CI 워크플로 성공 확인

## 의도적으로 제외한 항목 (검토됨, 조치 안 함)

- **GH Actions(06:00)+Render(07:00) 이중 실행** — KISTA 멱등 처리로 의도된 이중화 (CLAUDE.md 기록됨)
- **Gemini quota 카운터가 환경별 독립** — 정보성 알림이라 정확도 손실 허용
- **`Order.OrderType`의 미사용 LOC/MOC** — KISTA API 스펙 호환 도메인 모델링, 유지
- **from-image 업로드 시 JPEG도 `image/png`로 라벨링** — Gemini가 관대하게 처리, 운영 문제 없음
- **스크래퍼의 sleep 기반 대기** — 취약하지만 동작 중, 실패 시 텔레그램 알림으로 관측 가능
