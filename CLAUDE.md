# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
bash gradlew build                       # 컴파일 + 테스트 + JAR
bash gradlew test                        # 전체 테스트 (JUnit 5, 병렬 실행)
bash gradlew test --tests "com.fida.architecture.HexagonalArchitectureTest"  # 아키텍처 규칙만
bash gradlew bootJar                     # app.jar 빌드 (테스트 생략)
bash gradlew bootRun --args='--spring.profiles.active=local'  # 로컬 실행
docker compose up --build                # 컨테이너 빌드 및 실행
docker compose run --rm -e SPRING_PROFILES_ACTIVE=job -e SPRING_MAIN_WEB_APPLICATION_TYPE=none fida  # GitHub Actions one-shot 로컬 테스트
docker compose build <service> && docker compose up -d --force-recreate <service>  # 설정 변경 후 이미지 재빌드 + 컨테이너 강제 재생성
docker compose build --no-cache <service>        # 소스 변경 후 캐시 의심 시 강제 재빌드
docker run --rm -e KEY=val ... <image>           # 컨테이너 기동 오류 빠른 확인
gh run list --repo narafu/fida --limit 5         # GitHub Actions 최근 실행 목록
gh run view <run-id> --log-failed                # 실패 실행 로그 확인
```

## Architecture

Hexagonal Architecture (Ports & Adapters) — ArchUnit 테스트로 의존성 방향 강제 검증됨.

```
domain/model/     ← 외부 의존성 0 (Spring 어노테이션 금지)
domain/port/in/   ← UseCase 인터페이스
domain/port/out/  ← 외부 연동 포트 인터페이스
application/service/  ← UseCase 구현체 (@Service만 허용, adapter 직접 참조 금지)
adapter/in/       ← 인바운드 (schedule, web) — application 구현체 직접 참조 금지
adapter/out/      ← 아웃바운드 (scraper, ocr, sheets, notify, kista)
playwright-server/  ← Node.js 사이드카 (Java로 이식 금지)
```

의존성 방향: `adapter → application(port 경유) → domain`

## Key Constraints

- FIDA 서버 포트: **7070** (KISTA가 8080 사용 중). 포트 변경 시 3곳 동기화 필요: `application.yml`, `Dockerfile EXPOSE`, `docker-compose.yml` healthcheck URL
- DB 없음 (JPA/DataSource 추가 금지), RestTemplate 전용 (WebClient 금지)
- Gemini 모델: `gemini-2.5-flash-lite` 고정
- 스케줄: 화~토 07:00 KST (`cron = "0 0 7 * * TUE-SAT"`, 변경 금지)
- Google Sheets 셀 범위 고정: `A1, C2:D4, C5:D7, A8, C8, D8`
  - **A8 = "현사이클 시작"** (`current_cycle_start`) — "잔금(cash_balance)"과 혼동 금지
  - A1 날짜 결정: 제목 M/D 패턴 1순위 → scraper postDate 2순위 → `LocalDate.now()` 3순위 (`FandingScraperAdapter.resolveDateFromTitle()`)
- Virtual Threads 활성화 (`spring.threads.virtual.enabled=true`)
- springdoc = "2.8.4" (`gradle/libs.versions.toml`) — 2.6.x는 Spring Boot 3.4.x(Spring Framework 6.2)와 `NoSuchMethodError: ControllerAdviceBean` 충돌 있어 2.7.0+ 유지 필요
- Docker: ZGC + MaxRAMPercentage=75.0, non-root 실행
- GitHub Actions one-shot 실행: `SPRING_PROFILES_ACTIVE=job` + `SPRING_MAIN_WEB_APPLICATION_TYPE=none` 필수 조합 — 후자 없으면 ApplicationRunner 완료 후에도 웹서버가 살아 컨테이너 무한 대기
- Spring Profile: `job` = `FandingRunner`(one-shot) 활성, `FandingScheduler` 비활성 / `!job`(기본) = 반대
- Mockito로 RestTemplate 테스트 시: `postForObject`는 varargs(`Object... uriVars`)가 있어 URL 매처로 `any()` 대신 `anyString()` 사용 필요
- Lombok `1.18.36` 적용됨 — `@RequiredArgsConstructor` + `@Slf4j` 사용
  - `@Value` 설정 필드: **non-final로 선언할 것** — `copyableAnnotations`가 CI 환경에서 적용되지 않아 `final`+`@Value` 조합은 GitHub Actions에서 `No qualifying bean of type 'String'` 오류 발생. `@RequiredArgsConstructor`에서 제외되고 Spring이 필드 주입으로 처리함 (`KistaAdapter`, `TelegramAdapter` 모두 non-final)
  - 어댑터 테스트에서 non-final `@Value` 필드 값 설정: `ReflectionTestUtils.setField(adapter, "fieldName", value)`
  - `TradingRecordService`는 `@Lazy SheetPort` 파라미터 때문에 `@RequiredArgsConstructor` 미적용 — 명시적 생성자 유지

## Current Status

- 소스 코드: **모든 구현 완료** (KistaAdapter, FidaOrderController 포함 12개 태스크 completed)
- 구현 태스크는 shrimp-task-manager로 관리 중 (`list_tasks`로 확인)
- **GitHub Actions Cron 전환 완료 및 검증 완료** — `.github/workflows/fida-schedule.yml` (UTC `0 22 * * 1-5` = KST 화~토 07:00)
- GitHub Secrets 등록 시 service-account.json: `base64 -i <경로>/service-account.json | tr -d '\n'` → `GOOGLE_SERVICE_ACCOUNT_JSON_B64`로 등록
- KISTA 프로젝트: https://github.com/narafu/kista.git (별도 프로젝트, FIDA가 전송한 주문을 수신해 KIS API로 실행)
- **KISTA 주문 전송 활성화됨** — `TradingRecordService.process()`: sheet 기록 → 매매 알림 → KISTA 전송 순서. KISTA 실패는 sheet/매매 알림에 영향 없음 (`safeNotify` 패턴)
- **KISTA 전송 결과(성공/실패)는 별도 텔레그램 메시지로 알림** — 매매 알림과 독립된 2개의 메시지
- **KISTA 성공 알림**: 저장된 `UUID id` 앞 8자리 포함 (`KistaPort.sendOrders()` → `UUID` 반환, `KistaOrderResponse` DTO로 역직렬화)

## Task Management

- 작업 시작 전 `list_tasks`로 현재 태스크 확인
- 태스크 상태 전이: `execute_task` (pending→in_progress) → 구현 → `verify_task` (in_progress→completed)
- `verify_task` 직접 호출 불가 — 반드시 `execute_task` 먼저 호출해야 함
- pending 태스크도 실제 코드가 이미 구현되어 있을 수 있으므로, `execute_task` 전에 현재 파일 상태 확인 권장
- 규칙 초기화/변경 시: `init_project_rules` → `process_thought` → `split_tasks`
- 태스크 추가: `split_tasks` with `updateMode: append`
- 태스크 의존성 고아 발생 시 (split_tasks 재실행 후 stale ID 참조): `update_task(taskId, dependencies: [])` 로 수정

## Secrets

- `./secrets/service-account.json` — 로컬 원본: `/Users/phs/secret/google-sheet-secret.json` 복사. 디렉토리가 비어 있으면 스케줄러 실행 시 `FileNotFoundException` 발생. `secrets/`는 `.gitignore`에 등록돼 있어 git에 올라가지 않음
- Render 배포 시: 대시보드 → Secret Files → 경로 `/secrets/service-account.json`, 파일 내용 붙여넣기

## Environment Variables

`.env` 파일 사용 (`.env.example` 참조). 신규 변수 추가 시 `.env.example`도 반드시 동기화.

| 변수 | 설명 |
|------|------|
| `GEMINI_API_KEY` | Gemini Vision API |
| `FANDING_EMAIL` / `FANDING_PASSWORD` | fanding.kr 로그인 (playwright-server 전달) |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | 시트 ID |
| `GOOGLE_SERVICE_ACCOUNT_JSON_PATH` | `/secrets/service-account.json` |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | 텔레그램 알림 |
| `SCRAPER_URL` | `application.yml` 기본값 `http://playwright-server:3000/scrape` — `.env` 설정 불필요 |
| `KISTA_URL` | `application.yml` 기본값 빈 문자열, KistaAdapter Bean 미등록 — `.env` 설정 불필요 |

## Design Reference

- n8n 원본 프로젝트 (비교 참조용): https://github.com/narafu/fanding-auto.git
- fanding-auto 구현 파리티: 2026-05-07 전면 비교 완료. n8n의 `cash_balance`는 Gemini 프롬프트에 없어 항상 `-`이므로 FIDA의 `currentCycleStart` 표시가 올바름. 날짜 버그 외 추가 차이 없음
- 전체 설계: `/home/user/.claude/plans/fanding-auto-trade-kis-n8n-linked-fountain.md` (FIDA 섹션)
- 프로젝트 규칙 상세: `shrimp-rules.md` (Task Manager 자동 참조)
- 어댑터 규칙 + File Interaction Rules: `src/main/java/com/fida/adapter/CLAUDE.md`
- 도메인 제약: `src/main/java/com/fida/domain/CLAUDE.md`
- playwright-server 특이사항: `playwright-server/CLAUDE.md`
- KISTA API 스펙: `POST /api/internal/fida-orders` body `{tradeDate, ticker, currentCycleStart, currentCycleRealizedPnl, avgPrice, holdings, orders[{orderType, direction, quantity, price}]}` — OpenAPI docs: `{KISTA_URL}/api-docs`
  - 응답: `{id(UUID), tradeDate, ticker, ...}` — `KistaOrderResponse(UUID id)`로 역직렬화 (나머지 필드는 무시)
  - 요청 헤더: `X-Internal-Token: ${kista.internal-token}` (내부 인증)
- KISTA ticker: `"SOXL"` 고정 (`KistaAdapter` 상수). orders 배열은 BUY 먼저 SELL 나중 순서. null price → ZERO, `"전부"` qty → null (전량 주문), null/비파싱 qty → 0 fallback
- `Order.quantity` 타입: `Integer` (nullable) — `"전부"` 입력 시 null 전송으로 KISTA 전량 주문 처리
