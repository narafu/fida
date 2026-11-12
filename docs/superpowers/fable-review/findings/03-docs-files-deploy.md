# 03. 문서 · 불필요 파일 · 배포 구성 (읽기 전용 측정, 2026-11-12, HEAD a1e8bfc)

## 1. 문서 목록 (git 추적 .md 16개, 합계 2,359줄 / 112,466B)

| 파일 | 줄 | 바이트 | 자동 로드 | 비고 |
|---|---|---|---|---|
| CLAUDE.md | 161 | 15,437 | 예 (루트, 매 세션) | 가장 큼. Current Status 절(L98~124)이 완료 이력 나열 |
| AGENTS.md | 8 | 376 | 아니오(Codex류용) | CLAUDE.md로의 포인터뿐 |
| src/main/java/com/fida/adapter/CLAUDE.md | 38 | 3,408 | 예 (adapter 파일 접근 시 지연 로드) | |
| src/main/java/com/fida/domain/CLAUDE.md | 29 | 1,021 | 예 (domain 접근 시) | Port 표가 코드로 확인 가능한 중복 |
| playwright-server/CLAUDE.md | 26 | 1,169 | 예 (해당 디렉토리 접근 시) | |
| shrimp-rules.md | 239 | 9,105 | 아니오 (@import 없음; "Task Manager 자동 참조"라고만 CLAUDE.md L153에 기재) | 대부분 낡음, 아래 참조 |
| deploy/server/README.md | 128 | 6,949 | 아니오 | 배포 런북, 유효 |
| docs/oci-migration-plan.md | 105 | 9,235 | 아니오 | 상단에 "정정" 배너, 과거 결정 기록 |
| docs/superpowers/plans/2026-05-10-github-actions-cron.md | 368 | 11,547 | 아니오 | 체크박스 0/22 완료 표기, 실제론 구현·폐기됨 |
| docs/superpowers/plans/2026-07-10-fida-health-check.md | 740 | 33,904 | 아니오 | 체크 0/39, 가장 큰 문서. 실행 완료(.superpowers/sdd/progress.md 7개 태스크 complete) |
| docs/superpowers/plans/2026-07-16-ocr-holdings-priority.md | 53 | 2,756 | 아니오 | 체크 0/6 |
| docs/superpowers/plans/2026-07-26-ocr-sell-rows.md | 120 | 4,429 | 아니오 | 체크 0/8 |
| docs/superpowers/specs/2026-05-10-github-actions-cron-design.md | 207 | 7,219 | 아니오 | Render 무료 플랜 근거 서술 (폐기됨) |
| docs/superpowers/specs/2026-06-13-swagger-design.md | 51 | 1,720 | 아니오 | |
| docs/superpowers/specs/2026-07-03-ocr-holdings-fallback-design.md | 37 | 1,537 | 아니오 | |
| docs/superpowers/specs/2026-07-26-ocr-sell-rows-design.md | 49 | 2,654 | 아니오 | |

README*는 존재하지 않음(루트·playwright-server 모두 없음). 로컬 비추적 `.superpowers/sdd/`(176K, task brief/report 14개+diff 2개)와 `docs/superpowers/fable-review/`(이번 조사 산출물)는 git 미추적.
docs/ 전체 = 1,988줄·약 76KB로 어떤 CLAUDE.md/코드에서도 링크되지 않음(grep: CLAUDE.md·AGENTS.md·deploy·.github에서 `docs/superpowers`, `oci-migration-plan` 참조 0건).

### 1-a. 문서 간 중복
- AGENTS.md는 CLAUDE.md 목록 재서술(8줄, 의도된 포인터).
- adapter/CLAUDE.md "File Interaction Rules" 표 ≡ domain/CLAUDE.md "필드 변경 시 동기화 체크리스트"(3행 동일: domain/model, ParsedOrder, TradingRecord) ≡ shrimp-rules.md "Key File Interaction Standards" ≡ 루트 CLAUDE.md 환경변수 규칙(`.env.example` 동기화) -- 3~4곳 중복.
- 환경변수 목록: 루트 CLAUDE.md 표 / `.env.example`(11줄) / deploy/server/README.md ".env 내용" / shrimp-rules.md 표 -- 4곳.
- 서버 배포 절차: CLAUDE.md L104(긴 단락) / deploy/server/README.md "배포 흐름" / docs/oci-migration-plan.md §3,4 -- 3곳. 배포 가드 시간대가 서로 다름(아래).
- domain/CLAUDE.md Port·UseCase 표는 `domain/port/**` 파일명으로 자명(5+2행).
- playwright-server/CLAUDE.md의 healthcheck 서술은 docker-compose.yml 2개에 그대로 있음.
- 루트 CLAUDE.md "Key Constraints"의 Gemini 429 분류 ≡ adapter/CLAUDE.md "아웃바운드 OCR" 2번째 항목(중복); springdoc 버전도 양쪽 참조.
- 메모리 project_oci_migration_complete.md가 "상세는 CLAUDE.md가 canonical" 이라며 의도적으로 얇게 유지(중복 아님).

### 1-b. 코드/파일과 어긋난 서술 (실제 대조 확인분만)
**shrimp-rules.md (대부분 부패, 239줄)**
- L8 `기반 프로젝트: ../claude-starter-kit` -- 외부 상위 경로, 레포 내 없음.
- L55~56 / L151 KISTA 엔드포인트 `POST http://kista:8080/api/fida/orders`, body `{date,buy,sell,cashBalance...}` -- 실제는 `KistaAdapter.INTERNAL_ORDER_PATH="/api/internal/fida-orders"`, 기본 URL `https://api.kista-app.com`(application.yml:53).
- "KISTA_URL 미설정 시 호출 생략 / 실패 시 예외 전파 금지" -- 현재 기본값이 있어 항상 호출, 루트 CLAUDE.md는 "KISTA 전송 이후 첫 실패 예외 전파"로 정반대.
- L112 `A8 — 현금잔고` -- 코드는 `cell("A8", record.order().currentCycleStart())`(GoogleSheetsAdapter:102). 루트 CLAUDE.md가 "현사이클 시작, 현금잔고와 혼동 금지"라 명시한 바로 그 혼동이 shrimp-rules에 남아 있음.
- L20~35 디렉토리 트리: 실제 존재하는 HeartbeatPort/Adapter, Kista, FandingRunner, FidaOrderController, GeminiClient/Parser/Reconciler, exception/ 패키지 등 누락. "스케줄러 단독 실행"도 REST 트리거 존재와 불일치.
- L185~189 "서비스: fida+playwright-server 두 개만" -- 서버용은 caddy 포함 3개(deploy/server/docker-compose.yml). playwright healthcheck가 `wget` 이라 적혀 있으나 node:20-slim에 wget 없음(playwright-server/CLAUDE.md가 정반대 명시, 실제 compose는 node -e).
- 실제 Spreadsheet ID와 TELEGRAM_CHAT_ID 값이 평문 기재 -- 민감 값 노출(추적 파일). 값은 이 문서에 옮기지 않는다.
- "Key File Interaction: ParsedOrder → GeminiVisionAdapter 파싱" -- 현재는 OcrResultReconciler(adapter/CLAUDE.md는 올바름).
- L202 "RestTemplate 타임아웃: scraperRestTemplate 180초 / 공용 120초" -- 코드와 일치(RestTemplateConfig 120/180s). 이 부분만 유효.

**루트 CLAUDE.md**
- L103 "구현 태스크는 shrimp-task-manager로 관리 중 (`list_tasks`)" 및 L119~126 Task Management 절 -- tasks.json 12개 전부 completed, 마지막 변경 2026-04-29(1c7fd85). 현재 작업 흐름(superpowers plans/SDD)과 불일치.
- L100~101 "소스 코드: 모든 구현 완료 (… 12개 태스크 completed)" -- 완료 이력.
- L104 배포 가드 "06:50~07:45 KST"는 server-deploy.yml(`now<=745`)과 일치. 그러나 deploy/server/README.md L98은 "06:50~07:30", oci-migration-plan.md도 "06:50~07:30" -- README·plan이 낡음(CLAUDE.md는 맞음).
- L104~124 Current Status는 2026-08-04 사건(권한 2건, 개행 누락), healthchecks.io 설정 완료, Render/Fly 폐기 완료 등 완료 이력이 ~25줄. 폐기된 Render/Fly.io 서술은 "완전 폐기 완료" 이력 형태로만 남음(L111 `srv-d8m9...` ID, 불필요).
- L111 "2026-08-05 07:00 KST 다음 실행에서 확인 필요" -- 3개월 전 날짜, 검증 결과 미갱신.
- L126~131 Secrets: `/Users/phs/secret/google-sheet-secret.json` 로컬 경로(개인 환경)이고 README에도 동일 경로 중복.
- L68 `GH Actions one-shot` Commands(`docker compose run ... job`)는 fida-schedule.yml 존재로 유효.
- 존재하지 않는 파일 참조: `/Users/phs/.claude/plans/fanding-auto-trade-kis-n8n-linked-fountain.md`(L148, 레포 외부, 존재 미확인 -- 이 조사에서 열어보지 않음).

**deploy/server/README.md**
- L98 가드 시간대 06:50~07:30 (실제 07:45) 낡음.
- "수동 롤백" 예시(L124~127)가 `FIDA_IMAGE`만 export -- compose는 `PLAYWRIGHT_SERVER_IMAGE`도 `:?` 필수라 그대로 실행하면 실패.
- "최초 배포 후 수동 스모크 권장" 문단(L103~105)은 이미 지나간 최초 배포 안내.
- `.env` 샘플은 .env.example과 거의 동일하나 `KISTA_URL` 주석 형태 차이 정도. 접속 정보(user/key) 기재 없음.

**docs/**
- plans/specs 4쌍+1: 체크박스가 전부 미체크(0/75)인데 구현은 완료 -- 상태 표기 낡음. `2026-05-10-github-actions-cron` plan/spec은 "Render+GH Actions cron" 전제(L7 Render 플랜 검토)이며 CLAUDE.md상 cron 트리거는 제거됨. `2026-07-10-fida-health-check.md`는 740줄, `Render 헬스체크`, `GH Actions(06:00)+Render(07:00) 이중 실행` 등 폐기된 구조 서술(L42, L736).
- oci-migration-plan.md: 상단 배너가 `shared_net`·"kista-api와 동일 서버" 무효를 선언; 본문(§3 compose에 `networks: shared_net`, `security_opt: seccomp:unconfined`, 가드 06:50~07:30, 경로 `C:\Users\USER\workspace\kista\kista-api`)은 실제 deploy 파일과 다름.

**playwright-server/CLAUDE.md**: `GET /scrape`·`/health`·503 동시실행 방지 기술. scrape-server.js 확인 -- execFile 170s 등 일치. Gotchas 3번째(".gitignore의 `.env*`가 .env.example 차단")는 현재 .gitignore에 `!.env.example` 존재해 해결된 과거 사건.

## 2. 메모리 (/Users/phs/.claude/projects/-Users-phs-workspace-fida/memory/, 5파일 83줄 / 6,667B)

| 파일 | 줄 | B | 요약 | 중복/낡음 |
|---|---|---|---|---|
| MEMORY.md | 6 | 577 | 인덱스 4항목 | 정상 |
| feedback_commit_after_work.md | 14 | 621 | 작업 후 자동 커밋, push는 명시 요청만 | 전역 ~/.claude/CLAUDE.md "작업이 끝나면 스스로 커밋/push는 명시 요청" 및 프로젝트 CLAUDE.md Git 규칙과 완전 중복 -> 삭제 후보 |
| feedback_unification.md | 11 | 627 | 두 방식 병용/fallback 금지 | 전역·프로젝트 CLAUDE.md에 없는 선호 -> 유지 가치 있음 |
| project_oci_migration_complete.md | 15 | 1,203 | Fly/Render 폐기 완료; kista-api 서버 SSH 명령 기록 | 상단 설명이 CLAUDE.md Current Status 중복(스스로 인정). 유일한 고유 정보는 kista-api SSH 한 줄(아래 7번). "Render/Fly 제안 금지" 지침은 CLAUDE.md에도 있음 |
| project_improvement_backlog.md | 37 | 3,639 | 2026-10-30 백로그 ①~⑤, 대부분 "처리 완료" 기록 | **낡음**: ①에 "playwright mem_limit 2026-10-31 OOM으로 제거", ③에 "b84e5dd로 mem_limit 제거" -- 현재 deploy/server/docker-compose.yml은 `mem_limit: 3g`(97402d2에서 재도입). ③ "정상 소요 ~92s, 시리즈 스크롤 ~52s" -- 1934f47/7b58bf4로 스크롤 제거됨. 처리 완료 항목 서술이 대부분이라 규칙("처리한 항목은 제거")에 따라 정리 대상. 완료 이력이라 코드/git log로 대체 가능 |

## 3. 불필요 파일 후보 (git ls-files 103개 기준)

| 후보 | 근거 |
|---|---|
| `.shrimp-data/tasks.json`(480줄,33,330B) + `.shrimp-data/.gitignore` | 12/12 completed, 마지막 갱신 2026-04-29. 코드·워크플로·빌드에서 참조 0(참조는 CLAUDE.md L103,L153,L119~126뿐). 추적 파일 |
| `shrimp-rules.md`(239줄) | CLAUDE.md L153만 참조. 위 1-b의 다수 어긋남. 규칙 중 유효한 것(Hexagonal 금지사항, 셀 범위, RestTemplate 전용)은 루트 CLAUDE.md Key Constraints에 이미 있음 |
| `AGENTS.md`(8줄) | 포인터. Claude Code는 미사용. 다른 에이전트 도구 사용 여부 사용자 확인 필요(보류) |
| `docs/superpowers/plans/*` 4개 + `specs/*` 4개 (1,988줄) | 코드·문서 참조 0. 전부 구현 완료. 의사결정 기록은 git log(예: 7b58bf4 등)로 복원 가능. oci-migration-plan.md도 동일 |
| `bin/` (412K, 비추적, .gitignore:47 `bin`) | Eclipse/IDE 컴파일 잔재(bin/main/application-prod.yml 등). 현재 src/main/resources엔 application-prod.yml 없음(application.yml, application-local.yml만). 추적 아님 -> 로컬 삭제 후보, git 영향 없음 |
| `docker-compose.yml`(루트, 29줄) | 로컬 개발 + fida-schedule.yml(`docker compose run`)이 사용 -- 유지(참조 있음) |
| `.github/workflows/fida-schedule.yml`(69줄) | workflow_dispatch 전용. CLAUDE.md가 "수동 재실행 전용"으로 의도적 유지. 마지막으로 실제 실행했는지는 이 조사에서 `gh` 미조회 -> 사용 여부 불명. 서버 이전 후 사용 빈도 낮음 |
| `FandingRunner`(adapter/in/schedule) + `job` 프로필 | fida-schedule.yml이 유일한 사용처. 워크플로 폐기 시 함께 후보 |
| `docs`/`.superpowers/sdd/`(비추적, .superpowers/sdd/.gitignore `*`) | 로컬 진행 기록 176K, git 영향 없음 |
| `build/`(39M), `.gradle/`, `.idea/` | 이미 .gitignore 처리됨 |
| `.gitignore` 정리점 | "Querydsl 생성 파일", "Fly.io .fly/", `docker-compose.override.yml`, "application-prod.yml" 주석: 폐기/미사용 항목(Querydsl·JPA 없음, Fly 폐기). `build/generated/`, `build/reports/` 는 `build/`에 이미 포함되어 중복. `*.env`와 `.env*` 중복. `.superpowers/`는 .gitignore가 아니라 하위 .gitignore(`*`)로 처리됨 |
| 빈 디렉토리 | `.claude/worktrees`(.git/info/exclude 처리), `bin/default` 만 확인. 추적 빈 디렉토리 없음 |
| `playwright-server/package-lock.json`(1,209줄) | `npm ci` 필수(Dockerfile) -> 유지 |
| `gradle.properties`/`settings.gradle.kts` 등 | 이상 없음 |

참조 없음 근거 grep: `grep -rnE "docs/superpowers|oci-migration-plan|claude-starter-kit|shrimp" CLAUDE.md AGENTS.md shrimp-rules.md src/**/CLAUDE.md playwright-server/CLAUDE.md deploy .github` -> CLAUDE.md:103, CLAUDE.md:153, shrimp-rules.md:8 3건뿐.

## 4. shrimp-task-manager 사용 여부
- 흔적: `.shrimp-data/tasks.json`(12/12 completed), `shrimp-rules.md`, CLAUDE.md L103/L119~126/L153.
- git log(`-- .shrimp-data shrimp-rules.md`): 마지막 변경 78fdc82(타임아웃 문서, 2026-10-31, shrimp-rules.md 수정 포함), tasks.json은 f5f3c48/1c7fd85(2026-04-29) 이후 변경 없음 -> 6개월 이상 미사용. 이후 작업은 superpowers plan/SDD(2026-05-10~07-26 plans, .superpowers/sdd) 기반.
- 결론: Task Management 절·shrimp-rules.md·.shrimp-data 전부 사실상 비활성(참조처가 문서뿐). 단 shrimp MCP가 로컬에 아직 연결돼 있는지는 이 조사에서 미확인.

## 5. 배포 구성

| 파일 | 줄 | 역할 |
|---|---|---|
| Dockerfile | 33 | fida 이미지. builder `eclipse-temurin:21-jdk-alpine`(`--platform=$BUILDPLATFORM`), runtime `eclipse-temurin:21-jre-alpine`, non-root appuser, EXPOSE 7070, `JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -Duser.timezone=Asia/Seoul` |
| playwright-server/Dockerfile | 26 | `node:20-slim` + apt `chromium`, `PUPPETEER_SKIP_CHROMIUM_DOWNLOAD`, `npm ci --omit=dev`, USER node, EXPOSE 3000 |
| docker-compose.yml (루트) | 29 | 로컬/GH Actions용 build 방식. **mem_limit 없음**. fida: `./secrets:/secrets:ro`, `./.fida-state:/state`, healthcheck wget /actuator/health; playwright: env 2개, node healthcheck. 포트 publish 없음 |
| deploy/server/docker-compose.yml | 58 | 서버용 image 방식(GHCR). fida `mem_limit: 1536m`, expose 7070; playwright `mem_limit: 3g`; caddy `caddy:2.9-alpine` `mem_limit: 64m` ports 80/443, 볼륨 `./Caddyfile:ro`,`caddy_data`,`caddy_config`, 로그 json-file 20m x3. fida 볼륨 `./secrets:/secrets:ro`,`./.fida-state:/state` |
| deploy/server/Caddyfile | 20 | `{$FIDA_DOMAIN}`: `/actuator/health`만 reverse_proxy fida:7070(재시도 120s), 나머지 404 |
| deploy/server/README.md | 128 | 서버 레이아웃·초기 설정·시크릿·롤백 런북 |
| .dockerignore | 7 | 허용 목록(gradle/, gradlew, settings, build.gradle.kts, src/) |
| playwright-server/.dockerignore | 2 | node_modules, npm-debug.log |
| .github/workflows/ci.yml | 29 | PR 전용 `./gradlew build`, 실패 시 리포트 업로드, timeout 15m |
| .github/workflows/fida-schedule.yml | 69 | workflow_dispatch 전용 one-shot(`SPRING_PROFILES_ACTIVE=job`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`), Gemini quota cache restore/save |
| .github/workflows/server-deploy.yml | 225 | main push + workflow_dispatch(force 입력). jobs: verify(`bash gradlew test`, 15m) -> build(QEMU+buildx, linux/arm64 이미지 2개 GHCR push, tag=SHA+latest, 45m) -> deploy(20m, `concurrency: server-production`, environment production) |

### 배포 절차
1. **배포 가드**: 화~토(dow 2~6) KST 06:50~07:45(`now>=650 && now<=745`)이면 실패 종료. workflow_dispatch + `force=true`로 우회. 가드 실패 시 헬스게이트 스텝은 `steps.deploy_guard.outcome != 'failure'` 조건으로 건너뜀.
2. SSH 설정: `SERVER_SSH_KEY`를 `~/.ssh/deploy_key`에 기록, 호스트 키는 워크플로 `env.SERVER_HOST_KEY`(ed25519, `SHA256:mbdWKf1f...`)로 known_hosts 고정.
3. `scp`로 `deploy/server/docker-compose.yml`, `Caddyfile`을 `/opt/fida/`에 업로드(Caddyfile은 inode 유지 위해 scp 덮어쓰기 -- mv/rsync 금지 주석).
4. 서버에서: 현재 이미지를 `.prev-fida-image`, `.prev-playwright-image`에 저장 -> `docker login ghcr.io` -> `docker compose pull` -> `up -d --no-deps fida playwright-server` -> `up -d caddy` -> `docker exec fida-caddy caddy reload`(5회 재시도).
5. **헬스게이트**: fida·fida-playwright-server 둘 다 `healthy`가 될 때까지 10초 간격 최대 30회(5분). 통과 시 `docker image prune -f`. `unhealthy` 또는 타임아웃이면 이전 이미지로 `up -d --no-deps` 롤백 후 exit 1(이전 이미지 정보 없으면 수동 복구 안내).
- 롤백 한계: 이전 이미지 기록이 둘 다 있어야 자동 롤백됨(최초 배포 시 공란).

### 시크릿 / 환경변수 / 서버 배치
- GitHub Secrets: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_SSH_PORT`(기본 22), `GITHUB_TOKEN`(자동, GHCR). fida-schedule.yml 전용: `GOOGLE_SERVICE_ACCOUNT_JSON_B64`, `GEMINI_API_KEY`, `FANDING_EMAIL`, `FANDING_PASSWORD`, `GOOGLE_SHEETS_SPREADSHEET_ID`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `INTERNAL_API_TOKEN`, `KISTA_URL`(선택).
- 서버 `/opt/fida/.env`(서버에서 직접 관리, Actions가 덮어쓰지 않음): GEMINI_API_KEY, FANDING_EMAIL, FANDING_PASSWORD, GOOGLE_SHEETS_SPREADSHEET_ID, GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/secrets/service-account.json, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, INTERNAL_API_TOKEN, KISTA_URL, HEARTBEAT_URL, FIDA_DOMAIN(Caddyfile 치환; caddy 컨테이너엔 이 값만 주입), FIDA_SCHEDULER_ENABLED, GEMINI_QUOTA_USAGE_PATH=/state/gemini-quota-usage.json. compose 변수: `FIDA_IMAGE`, `PLAYWRIGHT_SERVER_IMAGE`(워크플로가 주입).
- 서버 배치: `/opt/fida/{.env,Caddyfile,docker-compose.yml,secrets/service-account.json,.fida-state/}` (secrets와 .fida-state는 uid 100:101 소유 필요).
- 불일치: `.env.example`에는 FIDA_DOMAIN/GEMINI_QUOTA_USAGE_PATH/SCRAPER_URL이 없음(서버 전용이라 의도로 보이나 CLAUDE.md "신규 변수 .env.example 동기화" 규칙과 부분 충돌). CLAUDE.md 표에는 있음.
- 서버 사양(README): OCI `VM.Standard.A1.Flex` arm64 1 OCPU, 6GB RAM, 부트볼륨 50GB, Ubuntu 24.04. 인스턴스 IP `168.110.113.178`(CLAUDE.md), 도메인 `fida.kista-app.com`.

## 6. 자원 사용 근거 (문서/로그/코드에 남은 수치)
- 서버 6GB 호스트에 대해 컨테이너 한도 합 = fida 1536m + playwright 3g + caddy 64m ≈ 4.6GiB (fida 평시 약 360MiB 실사용, compose 주석 deploy/server/docker-compose.yml:12).
- playwright mem_limit 이력(git log): fd8ec74(2026-10-30) 보안 보강에서 `mem_limit 1536m` 도입 -> 2026-10-31 시리즈 무한 스크롤로 Chromium 피크 ~1.8GiB 되어 OOM, 스크래핑 실패 -> b84e5dd 제한 제거 -> 1934f47 무한 스크롤 제거 후 피크 ~1.2GiB 실측 -> 97402d2 `mem_limit: 3g` 재도입. 7b58bf4에서 최신글 판별을 목록 API 직접 조회로 일원화(스크롤/링크 372개 순회 제거).
- 스크래핑 소요: 정상 ~91~92s(1 vCPU 기준; 로그인 ~25s, 시리즈 스크롤 ~52s, 스크린샷 ~2s) -- 메모리 백로그 및 scrape-server.js:51 주석. 스크롤 제거(1934f47) 이후 실측치는 문서에 없음. 타임아웃 설정: playwright execFile 170s < Java scraperRestTemplate readTimeout 180s (공용 120s).
- 파이프라인 전체 소요: b9e2441(2026-10-31)이 단계별 로그와 전체 소요 시간을 기록하도록 추가 -- 수치 자체는 레포 문서에 없음(서버 로그에서만 확인 가능).
- JVM: ZGC + MaxRAMPercentage=75% (1536m -> 힙 최대 ~1.1GiB).
- Gemini quota: `gemini.daily-limit` 프로퍼티, `.fida-state/gemini-quota-usage.json` 영속화(2026-08 사례: 소유권 문제로 3주간 카운트 리셋).
- 로컬 이미지(docker images): `fida-playwright-server:latest` 1.38GB(압축 358MB). fida 앱 이미지는 로컬에 없음(측정 불가). 로컬 디스크: `build/` 39M, `.gradle/` 1.4M, `bin/` 412K, `.idea/` 56K, `.superpowers/` 176K, git pack 417KiB(177커밋).
- 서버 디스크/GHCR 이미지 크기: 문서에 수치 없음. 서버 로그 로테이션: docker daemon 50m x 5, caddy 20m x 3.

## 7. 운영 서버 접속 방법 표기 위치 (접속하지 않음)
- fida 서버: CLAUDE.md L104에 IP `168.110.113.178`, 도메인 `fida.kista-app.com`, 배포경로 `/opt/fida/`만 기록. **SSH 사용자/키 경로는 fida 레포 어디에도 없음**(deploy/server/README.md는 `<server>` 플레이스홀더, GitHub Secrets `SERVER_USER`/`SERVER_SSH_KEY`/`SERVER_SSH_PORT`에만 존재). 서버 호스트 공개키는 server-deploy.yml:17 `SERVER_HOST_KEY`.
- 수동 트리거/터널: deploy/server/README.md "수동 트리거" 절(`docker exec fida wget ... /api/fida/orders`, `ssh -L 7070:...`).
- kista-api 서버(별도 인스턴스) 접속 정보: 메모리 project_oci_migration_complete.md에 ssh 명령 1줄 (fida 서버가 아님; 실제 접속은 로컬 `~/.ssh/config`의 Host `kista-server`/`fida-server` 사용; 같은 키/사용자가 fida 서버에도 쓰이는지는 문서에 없음, 가정 금지).
- 로컬 시크릿 원본 경로: `/Users/phs/secret/google-sheet-secret.json`(CLAUDE.md, README).
