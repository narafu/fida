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
- Docker: ZGC + MaxRAMPercentage=75.0, non-root 실행

## Current Status

- `docker-compose.yml` — 아직 starter-kit 기준 (postgres/grafana 포함). FIDA 전용으로 교체 필요 (fida + playwright-server 2개 서비스)
- 소스 코드: 스켈레톤 상태 (FidaApplication, GlobalExceptionHandler, HexagonalArchitectureTest만 존재)
- 구현 태스크는 shrimp-task-manager로 관리 중 (`list_tasks`로 확인)

## Task Management

- 작업 시작 전 `list_tasks`로 현재 태스크 확인
- 규칙 초기화/변경 시: `init_project_rules` → `process_thought` → `split_tasks`
- 태스크 추가: `split_tasks` with `updateMode: append`

## Design Reference

- 전체 설계: `/home/user/.claude/plans/fanding-auto-trade-kis-n8n-linked-fountain.md` (FIDA 섹션)
- 프로젝트 규칙 상세: `shrimp-rules.md` (Task Manager 자동 참조)
