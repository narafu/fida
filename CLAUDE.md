# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build                          # 컴파일 + 테스트 + JAR
./gradlew test                           # 전체 테스트 (JUnit 5, 병렬 실행)
./gradlew test --tests "com.fida.architecture.HexagonalArchitectureTest"  # 아키텍처 규칙만
./gradlew bootJar                        # app.jar 빌드 (테스트 생략)
./gradlew bootRun --args='--spring.profiles.active=local'  # 로컬 실행
docker compose up --build                # 컨테이너 빌드 및 실행
docker compose build <service> && docker compose up -d --force-recreate <service>  # 설정 변경 후 이미지 재빌드 + 컨테이너 강제 재생성
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

- DB 없음 (JPA/DataSource 추가 금지), RestTemplate 전용 (WebClient 금지)
- Gemini 모델: `gemini-2.5-flash-lite` 고정
- 스케줄: 화~토 07:00 KST (`cron = "0 0 7 * * TUE-SAT"`, 변경 금지)
- Google Sheets 셀 범위 고정: `A1, C2:D4, C5:D7, A8, C8, D8`
- Virtual Threads 활성화 (`spring.threads.virtual.enabled=true`)
- springdoc **2.7.0 이상** 필요 — 2.6.x는 Spring Boot 3.4.x(Spring Framework 6.2)와 `NoSuchMethodError: ControllerAdviceBean` 충돌. 현재 `springdoc = "2.6.0"` → **2.8.4로 업그레이드 필요** (`gradle/libs.versions.toml`)
- Docker: ZGC + MaxRAMPercentage=75.0, non-root 실행

## Current Status

- `docker-compose.yml` — starter-kit 기준 (postgres/grafana 포함). 태스크 #11에서 교체 예정 (fida + playwright-server 2개 서비스)
- 소스 코드: 프로젝트 초기화 완료. 도메인·어댑터 구현은 미완 (태스크 #3~#10 pending)
- 구현 태스크는 shrimp-task-manager로 관리 중 (`list_tasks`로 확인)

## Task Management

- 작업 시작 전 `list_tasks`로 현재 태스크 확인
- 태스크 상태 전이: `execute_task` (pending→in_progress) → 구현 → `verify_task` (in_progress→completed)
- `verify_task` 직접 호출 불가 — 반드시 `execute_task` 먼저 호출해야 함
- pending 태스크도 실제 코드가 이미 구현되어 있을 수 있으므로, `execute_task` 전에 현재 파일 상태 확인 권장
- 규칙 초기화/변경 시: `init_project_rules` → `process_thought` → `split_tasks`
- 태스크 추가: `split_tasks` with `updateMode: append`

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
| `KISTA_URL` | 미설정 시 KISTA 연동 생략 |

## File Interaction Rules

| 수정 파일 | 함께 수정 필요 |
|-----------|---------------|
| `domain/model` 필드 변경 | 관련 Port, TradingRecordService, 연관 Adapter |
| `ParsedOrder` 필드 변경 | `GeminiVisionAdapter` 파싱 로직 동기화 |
| `TradingRecord` 필드 변경 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 |
| 새 환경변수 추가 | `.env.example` 반드시 업데이트 |

## Design Reference

- 전체 설계: `/home/user/.claude/plans/fanding-auto-trade-kis-n8n-linked-fountain.md` (FIDA 섹션)
- 프로젝트 규칙 상세: `shrimp-rules.md` (Task Manager 자동 참조)
