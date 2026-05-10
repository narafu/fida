# GitHub Actions Cron 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FIDA를 GitHub Actions Cron으로 one-shot 실행 가능하게 전환한다. 기존 @Scheduled 방식은 로컬/서버 배포용으로 유지한다.

**Architecture:** Spring Profile `job`을 도입해 두 실행 모드를 분리한다. `job` 프로필에서는 `FandingRunner`(ApplicationRunner)가 `useCase.process()` 한 번 실행 후 JVM 종료, `!job`(기본) 프로필에서는 기존 `FandingScheduler`(@Scheduled)가 동작한다. GitHub Actions에서 `SPRING_PROFILES_ACTIVE=job` + `SPRING_MAIN_WEB_APPLICATION_TYPE=none` 환경변수로 one-shot 모드 진입.

**Tech Stack:** Spring Boot 3.4.x, Spring Profiles, GitHub Actions, Docker Compose, JUnit 5 + AssertJ

---

## 파일 맵

| 파일 | 변경 |
|------|------|
| `src/main/java/com/fida/adapter/in/schedule/FandingRunner.java` | 신규 생성 |
| `src/test/java/com/fida/adapter/in/schedule/FandingRunnerTest.java` | 신규 생성 |
| `src/main/java/com/fida/adapter/in/schedule/FandingScheduler.java` | `@Profile("!job")` 한 줄 추가 |
| `src/test/java/com/fida/adapter/in/schedule/FandingSchedulerTest.java` | `@Profile` 어노테이션 검증 테스트 추가 |
| `.github/workflows/fida-schedule.yml` | 신규 생성 |

---

## Task 1: FandingRunner (one-shot 실행기)

**Files:**
- Create: `src/main/java/com/fida/adapter/in/schedule/FandingRunner.java`
- Create: `src/test/java/com/fida/adapter/in/schedule/FandingRunnerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/fida/adapter/in/schedule/FandingRunnerTest.java` 를 생성한다:

```java
package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("FandingRunner 테스트")
class FandingRunnerTest {

    @Test
    @DisplayName("run()은 ProcessTradingRecordUseCase.process()를 위임 호출한다")
    void run_delegates_to_use_case() throws Exception {
        boolean[] called = {false};
        ProcessTradingRecordUseCase stub = () -> called[0] = true;
        FandingRunner runner = new FandingRunner(stub);

        runner.run(mock(ApplicationArguments.class));

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("FandingRunner는 @Profile(\"job\")으로 제한되어야 한다")
    void runner_is_restricted_to_job_profile() {
        Profile profile = FandingRunner.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("job");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
bash gradlew test --tests "com.fida.adapter.in.schedule.FandingRunnerTest"
```

예상 결과: **FAIL** — `FandingRunner` 클래스 없음으로 컴파일 오류

- [ ] **Step 3: FandingRunner 구현**

`src/main/java/com/fida/adapter/in/schedule/FandingRunner.java` 를 생성한다:

```java
package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("job")
@RequiredArgsConstructor
@Slf4j
public class FandingRunner implements ApplicationRunner {

    private final ProcessTradingRecordUseCase useCase;

    @Override
    public void run(ApplicationArguments args) {
        log.info("FIDA one-shot 실행 시작");
        useCase.process();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.fida.adapter.in.schedule.FandingRunnerTest"
```

예상 결과: **PASS** 2개 테스트 모두 통과

- [ ] **Step 5: 전체 테스트 통과 확인**

```bash
bash gradlew test
```

예상 결과: `TradingRecordServiceTest` 2건 외 모두 **PASS** (`TradingRecordServiceTest` 실패는 KISTA 주석 처리로 인한 의도적 실패 — 무시)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/fida/adapter/in/schedule/FandingRunner.java
git add src/test/java/com/fida/adapter/in/schedule/FandingRunnerTest.java
git commit -m "feat: add FandingRunner for GitHub Actions one-shot execution"
```

---

## Task 2: FandingScheduler에 @Profile("!job") 추가

**Files:**
- Modify: `src/main/java/com/fida/adapter/in/schedule/FandingScheduler.java`
- Modify: `src/test/java/com/fida/adapter/in/schedule/FandingSchedulerTest.java`

- [ ] **Step 1: 실패하는 테스트 추가**

`FandingSchedulerTest.java`에 테스트를 추가한다. 기존 코드 유지하고 새 테스트 메서드만 추가:

```java
// 기존 import에 추가:
import org.springframework.context.annotation.Profile;

// 기존 테스트 클래스 안에 추가:
@Test
@DisplayName("FandingScheduler는 @Profile(\"!job\")으로 job 프로필에서 비활성화되어야 한다")
void scheduler_is_disabled_in_job_profile() {
    Profile profile = FandingScheduler.class.getAnnotation(Profile.class);

    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("!job");
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
bash gradlew test --tests "com.fida.adapter.in.schedule.FandingSchedulerTest"
```

예상 결과: **FAIL** — `FandingScheduler`에 `@Profile` 어노테이션 없음

- [ ] **Step 3: FandingScheduler에 @Profile("!job") 추가**

`FandingScheduler.java`를 다음과 같이 수정한다 (`@Profile` import + 어노테이션 한 줄 추가):

```java
package com.fida.adapter.in.schedule;

import com.fida.domain.port.in.ProcessTradingRecordUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!job")
@RequiredArgsConstructor
public class FandingScheduler {

    private final ProcessTradingRecordUseCase useCase;

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() {
        useCase.process();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.fida.adapter.in.schedule.FandingSchedulerTest"
```

예상 결과: **PASS** 3개 테스트 모두 통과

- [ ] **Step 5: 전체 테스트 통과 확인**

```bash
bash gradlew test
```

예상 결과: Task 1과 동일 (`TradingRecordServiceTest` 2건 의도적 실패 제외 모두 PASS)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/fida/adapter/in/schedule/FandingScheduler.java
git add src/test/java/com/fida/adapter/in/schedule/FandingSchedulerTest.java
git commit -m "feat: restrict FandingScheduler to non-job profile"
```

---

## Task 3: GitHub Actions Workflow 작성

**Files:**
- Create: `.github/workflows/fida-schedule.yml`

- [ ] **Step 1: .github/workflows 디렉토리 생성**

```bash
mkdir -p .github/workflows
```

- [ ] **Step 2: fida-schedule.yml 작성**

`.github/workflows/fida-schedule.yml` 을 생성한다:

```yaml
name: FIDA 자동 실행

on:
  schedule:
    - cron: '0 22 * * 1-5'   # 화~토 07:00 KST (UTC 기준 월~금 22:00)
  workflow_dispatch:           # GitHub UI에서 수동 트리거 가능

jobs:
  run-fida:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: service-account.json 생성
        run: |
          mkdir -p secrets
          echo "${{ secrets.GOOGLE_SERVICE_ACCOUNT_JSON_B64 }}" | base64 -d > secrets/service-account.json

      - name: .env 파일 생성
        run: |
          echo "GEMINI_API_KEY=${{ secrets.GEMINI_API_KEY }}" >> .env
          echo "FANDING_EMAIL=${{ secrets.FANDING_EMAIL }}" >> .env
          echo "FANDING_PASSWORD=${{ secrets.FANDING_PASSWORD }}" >> .env
          echo "GOOGLE_SHEETS_SPREADSHEET_ID=${{ secrets.GOOGLE_SHEETS_SPREADSHEET_ID }}" >> .env
          echo "GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/secrets/service-account.json" >> .env
          echo "TELEGRAM_BOT_TOKEN=${{ secrets.TELEGRAM_BOT_TOKEN }}" >> .env
          echo "TELEGRAM_CHAT_ID=${{ secrets.TELEGRAM_CHAT_ID }}" >> .env
          echo "SCRAPER_URL=http://playwright-server:3000/scrape" >> .env
          echo "KISTA_URL=" >> .env

      - name: FIDA 실행
        run: |
          docker compose run --rm \
            -e SPRING_PROFILES_ACTIVE=job \
            -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
            fida

      - name: 정리
        if: always()
        run: docker compose down
```

- [ ] **Step 3: YAML 문법 검증**

```bash
# python3로 YAML 파싱 검증
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/fida-schedule.yml'))" && echo "YAML valid"
```

예상 결과: `YAML valid`

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/fida-schedule.yml
git commit -m "feat: add GitHub Actions cron workflow for FIDA one-shot execution"
```

---

## Task 4: GitHub Secrets 등록 및 수동 트리거 검증

> 코드 변경 없음 — 인프라 설정 및 검증 단계

- [ ] **Step 1: service-account.json base64 인코딩**

```bash
# macOS
base64 -i /Users/phs/secret/google-sheet-secret.json | tr -d '\n'
# 출력된 값을 GitHub Secret에 등록
```

- [ ] **Step 2: GitHub Secrets 등록**

GitHub → narafu/fida → Settings → Secrets and variables → Actions → New repository secret

등록할 Secret 목록:

| Secret 이름 | 값 출처 |
|-------------|---------|
| `GOOGLE_SERVICE_ACCOUNT_JSON_B64` | Step 1 base64 출력값 |
| `GEMINI_API_KEY` | 로컬 `.env` 파일 |
| `FANDING_EMAIL` | 로컬 `.env` 파일 |
| `FANDING_PASSWORD` | 로컬 `.env` 파일 |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | 로컬 `.env` 파일 |
| `TELEGRAM_BOT_TOKEN` | 로컬 `.env` 파일 |
| `TELEGRAM_CHAT_ID` | 로컬 `.env` 파일 |

- [ ] **Step 3: workflow_dispatch로 수동 트리거**

GitHub → narafu/fida → Actions → `FIDA 자동 실행` → `Run workflow` 버튼 클릭

- [ ] **Step 4: 실행 로그 확인**

Actions 탭에서 실행 중인 워크플로우 클릭 → `run-fida` job → `FIDA 실행` step에서 다음 로그 확인:

```
INFO  c.f.a.i.s.FandingRunner - FIDA one-shot 실행 시작
INFO  c.f.a.o.s.FandingScraperAdapter - ...
INFO  c.f.a.o.o.GeminiVisionAdapter - ...
INFO  c.f.a.o.s.GoogleSheetsAdapter - ...
INFO  c.f.a.o.n.TelegramAdapter - ...
```

- [ ] **Step 5: 결과 확인**

1. Google Sheets — A1(날짜), C2:D4(매수), C5:D7(매도), A8(현사이클), C8(평단), D8(보유) 업데이트 확인
2. Telegram — 알림 메시지 수신 확인
3. workflow 종료 코드 0 (성공) 확인

---

## 검증 요약

```bash
# 전체 테스트 (TradingRecordServiceTest 2건 의도적 실패 제외 PASS 확인)
bash gradlew test

# 아키텍처 규칙 확인
bash gradlew test --tests "com.fida.architecture.HexagonalArchitectureTest"

# 로컬 docker one-shot 테스트 (선택)
docker compose run --rm \
  -e SPRING_PROFILES_ACTIVE=job \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  fida
docker compose down
```
