# FIDA GitHub Actions Cron 전환 설계

## Context

FIDA는 fanding.kr 스크래핑 → Gemini OCR → Google Sheets 기록 → Telegram 알림을 담당하는 스케줄 전용 Spring Boot 서비스다.

Render 무료 플랜에서의 배포를 검토했으나 두 옵션 모두 불가:
- **별도 서비스**: playwright-server(300~400MB) + Spring Boot(200~250MB) 합산 시 512MB 초과, 서비스 3개 × 730h = 750h 초과
- **KISTA 통합**: 512MB 초과 + 아키텍처 오염 (KISTA는 PostgreSQL/KIS API, FIDA는 DB 없음)

또한 FIDA는 HTTP 트래픽이 없는 순수 스케줄러이므로 15분 비활동 후 슬립 → 스케줄 실행 불가.

**결론**: GitHub Actions Cron을 사용해 외부에서 스케줄을 관리하고, FIDA를 one-shot 실행 후 종료하는 방식으로 전환한다.

---

## 설계

### 실행 모드 전략

기존 `@Scheduled` 모드와 GitHub Actions one-shot 모드를 Spring Profile로 분리해 공존.

| 모드 | 트리거 | SPRING_PROFILES_ACTIVE | 용도 |
|------|--------|------------------------|------|
| 기본 | `@Scheduled` 자동 | (없음) | 로컬 개발, 향후 유료 플랜 배포 |
| job | GitHub Actions → one-shot | `job` | GitHub Actions Cron |

### Cron 타이밍

`0 22 * * 1-5` (UTC) = **화~토 07:00 KST**

`workflow_dispatch` 추가 → GitHub UI에서 수동 트리거 가능.

### 실행 흐름 (job 모드)

```
GitHub Actions Cron (월~금 22:00 UTC)
  └─ actions/checkout (narafu/fida)
  └─ secrets/ 및 .env 파일 생성
  └─ docker compose run --rm \
       -e SPRING_PROFILES_ACTIVE=job \
       -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
       fida
       ├─ depends_on playwright-server: 자동 기동 + 헬스체크 대기
       └─ FandingRunner.run() → useCase.process() → JVM exit
  └─ docker compose down (always)
```

---

## 변경 파일 목록

### FIDA 레포 변경 (3개 파일)

#### 1. `src/main/java/com/fida/adapter/in/schedule/FandingRunner.java` (신규)

```java
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

- `@Profile("job")` — job 프로필에서만 Bean 등록
- `ApplicationRunner` — Spring Boot 시작 직후 실행, 완료 후 JVM 종료
- `ProcessTradingRecordUseCase` — 기존 포트 인터페이스 재사용, 도메인 계층 무변경

#### 2. `src/main/java/com/fida/adapter/in/schedule/FandingScheduler.java` (수정)

```java
@Component
@Profile("!job")    // ← 이 줄만 추가
@RequiredArgsConstructor
public class FandingScheduler {
    private final ProcessTradingRecordUseCase useCase;

    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
    public void run() { useCase.process(); }
}
```

- `@Profile("!job")` — job 프로필에서는 Bean 미등록 (FandingRunner와 충돌 방지)
- 기존 `@Scheduled` 로직 완전 유지

#### 3. `.github/workflows/fida-schedule.yml` (신규)

```yaml
name: FIDA 자동 실행

on:
  schedule:
    - cron: '0 22 * * 1-5'   # 화~토 07:00 KST (UTC 기준 월~금 22:00)
  workflow_dispatch:           # 수동 트리거

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

**주요 설계 결정:**
- `docker compose run --rm fida` — `depends_on` 덕분에 playwright-server 자동 기동 + 헬스체크 대기 후 fida 실행
- `SPRING_MAIN_WEB_APPLICATION_TYPE=none` — 환경변수로 주입, application.yml 무변경
- `SPRING_PROFILES_ACTIVE=job` — FandingRunner 활성화, FandingScheduler 비활성화
- `if: always()` — 실패해도 컨테이너 정리 보장
- service-account.json: base64 인코딩해서 GitHub Secret `GOOGLE_SERVICE_ACCOUNT_JSON_B64`로 저장

---

## GitHub Secrets 등록 목록

runner 레포(fida)의 Settings → Secrets and variables → Actions에 등록:

| Secret 이름 | 내용 | 기준 파일 |
|-------------|------|----------|
| `GOOGLE_SERVICE_ACCOUNT_JSON_B64` | `base64 -i secrets/service-account.json` 출력값 | 로컬 시크릿 파일 |
| `GEMINI_API_KEY` | Gemini Vision API 키 | .env |
| `FANDING_EMAIL` | fanding.kr 로그인 이메일 | .env |
| `FANDING_PASSWORD` | fanding.kr 로그인 패스워드 | .env |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | Google Sheets ID | .env |
| `TELEGRAM_BOT_TOKEN` | 텔레그램 봇 토큰 | .env |
| `TELEGRAM_CHAT_ID` | 텔레그램 채팅 ID | .env |

---

## 검증 계획

### 1. 로컬 테스트

```bash
# job 프로필로 로컬 실행 (docker 없이)
./gradlew bootRun --args='--spring.profiles.active=job --spring.main.web-application-type=none'
# → FandingRunner 실행 → process() → 종료 확인

# 기존 방식 무변경 확인
./gradlew test
```

### 2. Docker 테스트

```bash
docker compose build fida
docker compose run --rm \
  -e SPRING_PROFILES_ACTIVE=job \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  fida
# → 실행 후 컨테이너 자동 종료 확인
docker compose down
```

### 3. GitHub Actions 테스트

1. GitHub Secrets 등록 완료 후
2. Actions 탭 → `FIDA 자동 실행` → `Run workflow` (수동 트리거)
3. 실행 로그에서 `FIDA one-shot 실행 시작` 로그 확인
4. Google Sheets 업데이트 및 Telegram 알림 수신 확인

### 4. 스케줄 확인

- 다음 화요일 07:00 KST (월요일 22:00 UTC) 자동 실행 로그 확인
- Actions 탭 → Workflow runs 기록 확인

---

## 비고

- FIDA 서버 포트 7070, actuator, springdoc 설정은 job 모드에서 로드되지 않지만 **application.yml 변경 불필요** (web-application-type=none 환경변수로 처리)
- `TradingRecordServiceTest` 2건 실패는 KISTA 주석 처리로 인한 **의도적 실패** — 수정 불필요
- `FandingScheduler`의 `@Profile("!job")` 추가로 기존 테스트가 있다면 `@ActiveProfiles` 없이도 통과 (기본 프로필 = !job)
