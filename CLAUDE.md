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
docker compose build <service> && docker compose up -d --force-recreate <service>  # 설정 변경 후 이미지 재빌드 + 컨테이너 강제 재생성
docker compose build --no-cache <service>        # 소스 변경 후 캐시 의심 시 강제 재빌드
docker run --rm -e KEY=val ... <image>           # 컨테이너 기동 오류 빠른 확인
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
- Virtual Threads 활성화 (`spring.threads.virtual.enabled=true`)
- springdoc = "2.8.4" (`gradle/libs.versions.toml`) — 2.6.x는 Spring Boot 3.4.x(Spring Framework 6.2)와 `NoSuchMethodError: ControllerAdviceBean` 충돌 있어 2.7.0+ 유지 필요
- Docker: ZGC + MaxRAMPercentage=75.0, non-root 실행
- Mockito로 RestTemplate 테스트 시: `postForObject`는 varargs(`Object... uriVars`)가 있어 URL 매처로 `any()` 대신 `anyString()` 사용 필요
- Lombok `1.18.36` 적용됨 — `@RequiredArgsConstructor` + `@Slf4j` 사용
  - `@Value` 설정 필드는 **non-final** (필드 주입): Spring이 Lombok 생성 생성자 파라미터에서 `@Value`를 인식 못하는 문제 회피
  - 어댑터 테스트에서 non-final `@Value` 필드 값 설정: `ReflectionTestUtils.setField(adapter, "fieldName", value)`
  - `TradingRecordService`는 `@Lazy SheetPort` 파라미터 때문에 `@RequiredArgsConstructor` 미적용 — 명시적 생성자 유지

## Current Status

- 소스 코드: **모든 구현 완료** (KistaAdapter, FidaOrderController 포함 12개 태스크 completed)
- 구현 태스크는 shrimp-task-manager로 관리 중 (`list_tasks`로 확인)
- 다음 단계: OCI 배포
- KISTA 프로젝트: https://github.com/narafu/kista.git (별도 프로젝트, FIDA가 전송한 주문을 수신해 KIS API로 실행)
- **KISTA 주문 전송 현재 주석 처리 중** (`TradingRecordService.process()` 내 `kista.sendOrders()` 블록) — 시트 기록만 실행, 주문 전송 재개 시 주석 해제 필요. `TradingRecordServiceTest` 2건 실패는 이로 인한 **의도적 실패** (수정 불필요)

## Task Management

- 작업 시작 전 `list_tasks`로 현재 태스크 확인
- 태스크 상태 전이: `execute_task` (pending→in_progress) → 구현 → `verify_task` (in_progress→completed)
- `verify_task` 직접 호출 불가 — 반드시 `execute_task` 먼저 호출해야 함
- pending 태스크도 실제 코드가 이미 구현되어 있을 수 있으므로, `execute_task` 전에 현재 파일 상태 확인 권장
- 규칙 초기화/변경 시: `init_project_rules` → `process_thought` → `split_tasks`
- 태스크 추가: `split_tasks` with `updateMode: append`
- 태스크 의존성 고아 발생 시 (split_tasks 재실행 후 stale ID 참조): `update_task(taskId, dependencies: [])` 로 수정

## Environment Variables

`.env` 파일 사용 (`.env.example` 참조). 신규 변수 추가 시 `.env.example`도 반드시 동기화.

| 변수 | 설명 |
|------|------|
| `GEMINI_API_KEY` | Gemini Vision API |
| `FANDING_EMAIL` / `FANDING_PASSWORD` | fanding.kr 로그인 (playwright-server 전달) |
| `GOOGLE_SHEETS_SPREADSHEET_ID` | 시트 ID |
| `GOOGLE_SERVICE_ACCOUNT_JSON_PATH` | `/secrets/service-account.json` |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | 텔레그램 알림 |
| `SCRAPER_URL` | `http://playwright-server:3000/scrape` |
| `KISTA_URL` | 미설정(빈 문자열 기본값) 시 KistaAdapter Bean 미등록, KISTA 연동 완전 생략 |

## Design Reference

- n8n 원본 프로젝트 (비교 참조용): https://github.com/narafu/fanding-auto.git
- 전체 설계: `/home/user/.claude/plans/fanding-auto-trade-kis-n8n-linked-fountain.md` (FIDA 섹션)
- 프로젝트 규칙 상세: `shrimp-rules.md` (Task Manager 자동 참조)
- 어댑터 규칙 + File Interaction Rules: `src/main/java/com/fida/adapter/CLAUDE.md`
- 도메인 제약: `src/main/java/com/fida/domain/CLAUDE.md`
- playwright-server 특이사항: `playwright-server/CLAUDE.md`
- KISTA API 스펙: `POST /api/orders/fida` body `{symbol, direction(BUY|SELL), qty(optional), price}` — OpenAPI docs: `{KISTA_URL}/api-docs`
- KISTA symbol: `"SOXL"` 고정 (`KistaAdapter` 상수)
