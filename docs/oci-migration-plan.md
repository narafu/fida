> **[정정, 최신 결정]** 이 문서 작성 이후 "kista-api와 같은 인스턴스(shared_net)" 방침이 **별도의 독립 OCI 인스턴스**로 재확정됐다(Playwright/Gemini OCR의 튀는 부하를 매매 엔진과 격리하기 위함). 아래 원문의 `shared_net`/"kista-api와 동일 서버" 관련 서술은 더 이상 유효하지 않다 — 실제 구현은 `deploy/server/docker-compose.yml`(자체 Caddy 포함)·`deploy/server/Caddyfile`·`deploy/server/README.md`를 참고할 것. 이 파일은 과거 의사결정 기록으로만 남긴다.

---

# fida를 OCI 서버(kista-api와 동일 서버)로 이전

## Context

fida는 현재 Render 무료 티어(콜드스타트·메모리 제약)+GitHub Actions cron 이중화 구조로 운영 중이며, 이 구조 자체가 "GH Actions one-shot이 정식 실행 경로"인 이유였다. kista-api를 상시 기동 OCI 서버(2 OCPU/12GB, arm64)로 이전하면서 fida도 같은 서버에 올려 이 제약을 없앤다. kista-api 세션에서는 이미 Caddy·shared_net 변경 런북만 문서화해두고 실제 적용은 fida 커트오버 시점으로 보류해둔 상태(`kista-api` 저장소 커밋 `a334aca8`). 이번 작업은 fida 저장소가 독립적으로 소유하는 부분만 다룬다.

사용자 확정 사항 (재확인 불필요, 아래는 이미 결정된 값):
- Render 배포 **완전 폐기** (render.yaml 삭제, 관련 서술 제거)
- `secrets/service-account.json`은 서버에서 **직접 관리** (kista-api `.env` 패턴과 동일, Actions가 만들지 않음)
- `FIDA_DOMAIN=fida.kista-app.com` (kista-api `.env`에도 이 값을 사용자가 직접 반영해야 함 — fida 저장소 워크플로가 건드릴 수 없는 영역)

kista-api 저장소 로컬 경로(패턴 참고용, 이 문서에서 언급하는 `HeartbeatPort`/`HeartbeatAdapter`/`HeartbeatConfig`/`server-deploy.yml`/`fly-deploy.yml`/`deploy/server/docker-compose.yml`/`deploy/server/Caddyfile`/`deploy/server/README.md`가 모두 여기 있음):
```
C:\Users\USER\workspace\kista\kista-api\
```
- 참고: `src/main/java/com/kista/domain/port/out/HeartbeatPort.java`, `src/main/java/com/kista/adapter/out/heartbeat/{HeartbeatAdapter,HeartbeatConfig,HeartbeatProperties}.java`
- 참고: `.github/workflows/{server-deploy,fly-deploy}.yml`
- 참고: `deploy/server/{docker-compose.yml,Caddyfile,README.md}` (README.md의 "fida 병행 배포" 섹션, 커밋 `a334aca8`에 fida 쪽 셀프서브 요구사항 — shared_net/FIDA_DOMAIN 적용 순서 — 이 문서화되어 있음)

## 변경 파일 및 내용

### 1. 내부 스케줄러를 정식 경로로 승격
- `src/main/resources/application.yml`: 변경 없음 (`fida.scheduler.enabled` 기본값이 이미 `true`) — 서버 `.env`에서 `FIDA_SCHEDULER_ENABLED=true` 확인만 하면 됨
- `.env.example`: 이미 `FIDA_SCHEDULER_ENABLED=true` — 변경 불필요, `HEARTBEAT_URL=` 행 추가만 진행 (아래 4번)

### 2. `.github/workflows/fida-schedule.yml` — cron 제거, 수동 재실행 전용으로 축소
- `on.schedule` 블록 삭제, `on.workflow_dispatch`만 유지
- Gemini quota 캐시 restore/save 로직은 **유지** — workflow_dispatch로 특정 날짜 재처리 시에도 quota 추적이 필요하므로

### 3. `deploy/server/` 신설 — 서버 상시 배포용 compose (root의 `docker-compose.yml`은 로컬 개발·GH Actions one-shot 전용으로 그대로 둠, 변경 없음)
새 파일 `deploy/server/docker-compose.yml`:
```yaml
services:
  fida:
    image: ${FIDA_IMAGE:?FIDA_IMAGE is required}
    container_name: fida
    env_file: .env
    volumes:
      - ./secrets:/secrets:ro
      - ./.fida-state:/state
    depends_on:
      playwright-server:
        condition: service_healthy
    networks: [default, shared_net]
    expose: ["7070"]
    healthcheck: (기존 root compose와 동일: wget /actuator/health)
    restart: unless-stopped

  playwright-server:
    image: ${PLAYWRIGHT_SERVER_IMAGE:?PLAYWRIGHT_SERVER_IMAGE is required}
    container_name: fida-playwright-server
    environment: { FANDING_EMAIL, FANDING_PASSWORD }
    security_opt: [seccomp:unconfined]
    healthcheck: (기존과 동일)
    restart: unless-stopped

networks:
  shared_net:
    external: true   # kista-api Caddy가 fida:7070으로 reverse_proxy 하기 위한 결합점
```
- kista-api와 달리 fida는 자기 compose 안에 caddy가 없으므로 `--no-deps` 없이 `docker compose up -d` 그대로 사용 가능 (kista-api의 caddy 재시작 회피 문제가 해당 없음)
- 서버 `/opt/fida/.env`는 서버 관리자가 직접 관리 (Actions가 덮어쓰지 않음), `secrets/service-account.json`도 서버에 직접 배치

### 4. `.github/workflows/server-deploy.yml` 신설 (kista-api `server-deploy.yml` 패턴 참고, `DEPLOY_PATH=/opt/fida`)
- `verify`: `bash gradlew test` (fida는 DB 없음, postgres 서비스 불필요)
- `build`: fida 이미지 + playwright-server 이미지 **2개** GHCR arm64 빌드/푸시 (`ghcr.io/narafu/fida:sha`, `ghcr.io/narafu/fida-playwright-server:sha`)
- `deploy`: fida 자체 스케줄(화~토 07:00 KST) 실행 구간(06:50~07:30 KST)에는 배포 차단 — kista-api의 "자기 스케줄 보호" 패턴과 동일한 이유(배포로 실행 중인 스케줄 스레드가 강제 종료되는 것 방지), `workflow_dispatch` + `force=true`로 우회 가능
- SSH로 `deploy/server/docker-compose.yml` 업로드 → `docker compose pull && docker compose up -d` (fida에는 caddy가 없어 `--no-deps` 불필요)
- 헬스 게이트: `docker inspect --format '{{.State.Health.Status}}' fida` polling, 실패 시 이전 `FIDA_IMAGE`/`PLAYWRIGHT_SERVER_IMAGE` 태그로 자동 롤백 (kista-api 패턴)
- 신규 GitHub Secrets 필요 (사용자가 직접 등록 — 저장소 간 시크릿 공유 안 됨): `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_SSH_PORT`

### 5. Dead-man's switch — `HeartbeatPort`/`HeartbeatAdapter` 신설 (kista-api 패턴 참고, fida 컨벤션에 맞게 단순화)
kista-api는 `@ConfigurationProperties` record + 전용 `heartbeatRestTemplate` 빈을 쓰지만, fida는 이미 `RestTemplateConfig`에 범용 `restTemplate` 빈이 하나뿐이고 `@Value` non-final 필드 컨벤션(`KistaAdapter`, `TelegramAdapter` 참고)을 쓰므로 그대로 재사용:
- `domain/port/out/HeartbeatPort.java`: `void ping();` (fida는 스케줄이 1개뿐이라 kista처럼 open/close 구분 불필요)
- `adapter/out/heartbeat/HeartbeatAdapter.java`: 기존 `restTemplate` 빈 주입 + `@Value("${heartbeat.url:}") String url` (non-final) — url 비어있으면 핑 생략, 실패는 로그만 남기고 삼킴 (`safeNotify`류 패턴과 동일하게 무해)
- `application.yml`에 `heartbeat.url: ${HEARTBEAT_URL:}` 추가
- `.env.example`에 `HEARTBEAT_URL=` 행 추가 (테이블 문서화는 CLAUDE.md에서)
- `FandingScheduler.run()`의 `useCase.process()` 성공 직후(예외 발생 시엔 스킵되어야 미실행이 감지됨) `heartbeatPort.ping()` 호출
- healthchecks.io 콘솔에서 화~토 07:00 KST 기준 예상 주기 등록은 사용자 수동 작업

### 6. `render.yaml` 삭제

### 7. `CLAUDE.md` 갱신
- Key Constraints: "정식 자동 실행 경로는 GitHub Actions one-shot" → "정식 자동 실행 경로는 내부 `FandingScheduler`(OCI 상시 기동), GH Actions는 수동 재실행 전용"
- Current Status: Render 관련 문단(배포 URL, Secret Files, UptimeRobot) 전부 제거, OCI 배포 상태로 교체 (`/opt/fida/`, `FIDA_DOMAIN=fida.kista-app.com`, healthchecks.io dead-man's switch 추가 사실 기록)
- Commands 섹션: `render services list` 등 Render CLI 커맨드 제거
- Secrets 섹션: "Render Secret Files" 문단 제거 → "서버 `/opt/fida/secrets/service-account.json` 직접 배치" 로 교체
- Environment Variables 표: `HEARTBEAT_URL` 행 추가, `SCRAPER_URL`/`KISTA_URL` 설명 중 Render 전용 서술 정리

## 후속 수동 작업 (이 세션에서 실행 불가)
- fida GitHub repo Secrets 등록: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_SSH_PORT`
- 서버에 `/opt/fida/.env`, `/opt/fida/secrets/service-account.json` 직접 배치
- kista-api 서버 `.env`에 `FIDA_DOMAIN=fida.kista-app.com` 반영 + `docker network create shared_net` + kista-api `deploy/server/docker-compose.yml`/`Caddyfile` 커트오버 적용 (kista-api 저장소 쪽 작업, `a334aca8` 런북 참고)
- healthchecks.io 체크 생성 + `HEARTBEAT_URL` 값을 서버 `.env`에 반영
- Render 서비스(`srv-d8m9vqcm0tmc73ct17ug`) 대시보드에서 실제 삭제 (저장소 파일 삭제와 별개로 Render 콘솔 조작 필요 — 사용자가 직접 수행)

## 검증
- `bash gradlew test` — `HeartbeatAdapter` 단위 테스트(kista `HeartbeatAdapterTest` 패턴: url 없으면 미호출, 있으면 호출, 예외 삼킴) 포함 전체 통과
- `bash gradlew compileJava`
- 신규/변경 YAML(`server-deploy.yml`, `deploy/server/docker-compose.yml`, `fida-schedule.yml`)은 `docker compose -f deploy/server/docker-compose.yml config` 로 문법 검증 (실제 서버 배포는 수동 작업 완료 후 별도 진행)
- 커밋 전 코드 변경분(Java)은 별도 리뷰어 서브에이전트 검수 통과 필수 (CLAUDE.md 규칙)
