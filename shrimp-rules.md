# FIDA Development Guidelines

## Project Overview

- **목적**: fanding.kr 이미지 스크래핑 → Gemini Vision OCR → Google Sheets 저장 + Telegram 알림 (fanding-auto n8n 마이그레이션)
- **기술 스택**: Java 21, Spring Boot, Hexagonal Architecture, docker-compose
- **패키지 루트**: `com.fida`
- **기반 프로젝트**: `../claude-starter-kit` (복사 후 변형)
- **상태**: 무상태(DB 없음), 스케줄러 단독 실행 (화~토 07:00 KST)

---

## Project Architecture

### 디렉토리 구조

```
fida/
├── src/main/java/com/fida/
│   ├── domain/
│   │   ├── model/          # ScrapedPost, OrderItem, ParsedOrder, TradingRecord
│   │   └── port/
│   │       ├── in/         # ProcessTradingRecordUseCase
│   │       └── out/        # ScraperPort, OcrPort, SheetPort, NotifyPort
│   ├── application/
│   │   └── service/        # TradingRecordService
│   └── adapter/
│       ├── in/schedule/    # FandingScheduler
│       └── out/
│           ├── scraper/    # FandingScraperAdapter
│           ├── ocr/        # GeminiVisionAdapter
│           ├── sheets/     # GoogleSheetsAdapter
│           └── notify/     # TelegramAdapter
├── playwright-server/      # Node.js 사이드카 (절대 Java 프로젝트에서 수정 금지)
│   ├── scrape-server.js
│   ├── scrape-fanding.js
│   ├── package.json
│   └── Dockerfile
├── docker-compose.yml
├── .env.example
└── build.gradle.kts
```

### 의존성 방향 (절대 준수)

```
adapter → application → domain
```

- **domain**: 외부 의존성 Zero. Spring 어노테이션 금지.
- **application**: domain만 의존. `@Service`, `@RequiredArgsConstructor` 허용.
- **adapter**: application/domain 포트에만 의존. 구체 구현체 직접 참조 금지.

---

## Code Standards

### 도메인 모델

- **모든 domain/model 클래스는 Java `record` 사용** (클래스 금지, `TradingRecord` 예외: `static factory` 포함)
- `@Nullable` 어노테이션은 `org.springframework.lang.Nullable` 사용
- `BigDecimal` 연산은 항상 `RoundingMode` 명시

```java
// ✅ 올바름
public record OrderItem(@Nullable BigDecimal price, @Nullable String qty) {}

// ❌ 금지
public class OrderItem { private BigDecimal price; ... }
```

### Adapter 구현

- HTTP 클라이언트: **`RestTemplate` 사용** (WebClient, Feign 금지)
- `RestTemplate` 타임아웃: 스크래퍼 120초, 그 외 30초
- 실패 시 반드시 도메인 예외 throw (`ScraperException`, `OcrException` 등)

---

## Functionality Implementation Standards

### FandingScraperAdapter

- 엔드포인트: `GET http://playwright-server:3000/scrape` (환경변수 `SCRAPER_URL`)
- 응답 JSON 구조: `{ "postTitle": "...", "postDate": "YYYY-MM-DD", "postUrl": "...", "images": ["base64...", ...] }`
- 응답 JSON의 base64 이미지 → `byte[]` 디코딩 후 `ScrapedPost.images()`에 저장
- 실패 시 `ScraperException` throw

### GeminiVisionAdapter

- **모델**: `gemini-2.5-flash-lite` (변경 금지)
- **엔드포인트**: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent`
- 이미지 전송: `byte[]` → Base64 → `inline_data` (mimeType: `image/png`)
- 응답 파싱 순서: ` ```json ` 블록 먼저 시도 → 없으면 순수 JSON 파싱
- **데이터 보정 (필수)**:
  - `holdings < 0` → `0` 으로 강제 보정
  - `holdings == 0` → `avgPrice = null` 강제 설정

### GoogleSheetsAdapter

- 라이브러리: `google-api-services-sheets` (서비스 계정 JSON 파일 인증)
- **batchUpdate 모드**: `USER_ENTERED`
- **업데이트 셀 범위** (변경 금지):
  - `A1` — 날짜
  - `C2:D4` — 매수 주문 (최대 3행)
  - `C5:D7` — 매도 주문 (최대 3행)
  - `A8` — 현금잔고, `C8` — 평단가, `D8` — 보유수량
- 서비스 계정 JSON 경로: 환경변수 `GOOGLE_SERVICE_ACCOUNT_JSON_PATH`
- Spreadsheet ID: 환경변수 `GOOGLE_SHEETS_SPREADSHEET_ID`

### TelegramAdapter

- `POST https://api.telegram.org/bot{token}/sendMessage`
- 기존 n8n 텔레그램 메시지 포맷 그대로 유지
- 환경변수: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`

### FandingScheduler

```java
@Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Seoul")
public void run() { useCase.process(); }
```

- cron 표현식 및 timezone 변경 금지

### KistaAdapter (KISTA 연동 — 마지막 단계에서만 구현)

- **구현 시점**: `GoogleSheetsAdapter.update()` 완료 후 호출 (TradingRecordService 내부)
- 엔드포인트: `POST http://kista:8080/api/fida/orders` (환경변수 `KISTA_URL`)
- RestTemplate 사용, 타임아웃 30초
- 요청 바디:
  ```json
  {
    "date": "YYYY-MM-DD",
    "buy":  [{"price": 15.50, "qty": "100"}, ...],
    "sell": [{"price": 18.00, "qty": "ALL"}, ...],
    "cashBalance": 10000,
    "avgPrice": 14.20,
    "holdings": 500
  }
  ```
- **KISTA_URL 미설정 시 호출 생략** (optional 연동 — KISTA 서비스 미실행 환경 허용)
- 실패 시 로그 기록만 하고 예외 전파 금지 (Sheets/Telegram 성공이 우선)
- `domain/port/out/KistaPort.java` → `adapter/out/kista/KistaAdapter.java` 패턴 준수

---

## Dependency Standards

### build.gradle.kts 필수 의존성

```kotlin
// Google Sheets
implementation("com.google.apis:google-api-services-sheets:v4-rev20241201-2.0.0")
implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")

// Gemini, Telegram: HTTP 직접 호출 → 별도 라이브러리 추가 금지
```

- **JPA, Hibernate, DB 드라이버 추가 금지** (DB 없음)
- **WebFlux, WebClient 추가 금지**

---

## Environment Variables

`.env` 파일 사용. Vault, AWS Secrets Manager 등 외부 시크릿 관리 도입 금지.

| 변수명 | 설명 |
|--------|------|
| `GEMINI_API_KEY` | Gemini Vision API 키 |
| `FANDING_EMAIL` | fanding.kr 로그인 이메일 (playwright-server 전달용) |
| `FANDING_PASSWORD` | fanding.kr 로그인 비밀번호 |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | `1-RWelrmd-Ada0CZrZzNkMK9JdRyqnhfe38pH6kFtytQ` |
| `GOOGLE_SERVICE_ACCOUNT_JSON_PATH` | `/secrets/service-account.json` |
| `TELEGRAM_BOT_TOKEN` | 텔레그램 봇 토큰 |
| `TELEGRAM_CHAT_ID` | `6609029089` |
| `SCRAPER_URL` | `http://playwright-server:3000/scrape` |
| `KISTA_URL` | `http://kista:8080` (미설정 시 KISTA 연동 생략) |

- `.env.example`에 빈 값으로 항상 동기화 유지

---

## Docker Compose Standards

- 서비스: `fida` + `playwright-server` 두 개만 존재
- `fida`는 `playwright-server` healthcheck 통과 후 시작 (`depends_on: condition: service_healthy`)
- `playwright-server` healthcheck: `wget -qO- http://localhost:3000/health`
- `secrets/` 볼륨: `/secrets:ro` 마운트

---

## Key File Interaction Standards

| 파일 수정 시 | 함께 수정할 파일 |
|---|---|
| 새 환경변수 추가 | `.env.example`도 반드시 업데이트 |
| domain/model 필드 변경 | 해당 Port, TradingRecordService, 관련 Adapter 모두 확인 |
| `ParsedOrder` 필드 변경 | `GeminiVisionAdapter` 파싱 로직 동기화 필수 |
| `TradingRecord` 필드 변경 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 동기화 필수 |
| `build.gradle.kts` 의존성 추가 | `docker-compose.yml` 빌드 캐시 무효화 여부 확인 |
| `TradingRecord` → KISTA 연동 필드 변경 | `KistaAdapter` 요청 바디 구조 동기화 필수 |

---

## AI Decision Standards

### 새 기능 추가 시 결정 트리

```
1. domain/port/out에 새 Port 인터페이스 필요한가?
   → 예: Port 정의 → TradingRecordService에 주입 → adapter/out에 구현체 작성
   → 아니오: 기존 Port 확장 검토

2. 외부 API 호출인가?
   → 예: RestTemplate 사용, adapter/out 하위에 배치, 환경변수로 URL 주입
   → 아니오: domain/application 레이어에서 처리
```

### 모호한 상황 우선순위

1. **계층 위반 vs 기능 동작**: 계층 규칙 준수 우선
2. **새 라이브러리 도입 vs HTTP 직접 호출**: HTTP 직접 호출 우선 (Gemini, Telegram은 별도 SDK 금지)
3. **DB 저장 요청**: 무조건 거부 — 이 프로젝트는 무상태

---

## Prohibited Actions

- **domain 레이어에 Spring 어노테이션 추가** (`@Component`, `@Service`, `@Autowired` 등)
- **JPA Entity, Repository, DataSource 추가** (DB 없음)
- **WebClient, Feign Client 사용** (RestTemplate만 허용)
- **playwright-server/ 내 Node.js 코드를 Java로 이식** (사이드카 유지)
- **Gemini 모델 버전 변경** (gemini-2.5-flash-lite 고정)
- **스케줄 cron 또는 timezone 변경** (화~토 07:00 KST 고정)
- **Google Sheets 셀 범위 임의 변경** (A1, C2:D4, C5:D7, A8, C8, D8 고정)
- **adapter가 다른 adapter를 직접 참조** (반드시 Port 인터페이스 경유)
- **secrets/ 디렉토리를 git에 커밋** (.gitignore 확인 필수)
