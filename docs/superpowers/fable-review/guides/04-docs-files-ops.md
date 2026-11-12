# 04. 문서·불필요 파일·운영 잔재

근거는 `findings/03` 전체와 `findings/04`. 삭제와 대량 개편은 사용자 승인 뒤 실행한다.

## 현재 상태와 문제

- 추적되는 `.md` 16개 2,359줄. 그중 `docs/` 1,988줄은 어디에서도 참조하지 않고, 계획서 체크박스는 0/75로 남아 있다.
- 루트 `CLAUDE.md` 161줄(15KB)이 매 세션 자동 로드된다. `Current Status` 절 약 25줄은 끝난 일의 기록이다(2026-08-04 사건, Render 서비스 ID, "2026-08-05 확인 필요").
- `shrimp-rules.md` 239줄은 코드와 반대되는 내용이 여럿이다. A8을 "현금잔고"로 적었고(코드는 현사이클 시작), KISTA 엔드포인트가 옛 주소이고, 실제 Spreadsheet ID와 Telegram chat ID가 평문으로 들어 있다.
- `.shrimp-data/tasks.json` 480줄은 12건 전부 완료, 2026-04-29 이후 변경이 없다.
- 같은 내용이 여러 곳에 있다: 환경변수 표 4곳, 배포 절차 3곳, 파일 동기화 표 3곳.
- `deploy/server/README.md`의 배포 가드 시각이 07:30으로 낡았고(실제 07:45), 수동 롤백 예시는 `PLAYWRIGHT_SERVER_IMAGE`가 빠져 그대로 실행하면 실패한다.
- 서버 디스크 29GB 중 약 23GB가 이전 배포 이미지다. `docker image prune -f`는 태그 붙은 이미지를 지우지 못한다. 배포 약 11회 뒤 디스크가 찬다.
- 디렉토리 이름은 `playwright-server`인데 실제 의존은 puppeteer다.

## 목표 구조

문서는 세 종류만 남긴다.

| 파일 | 내용 | 목표 줄 수 |
|---|---|---|
| 루트 `CLAUDE.md` | 코드로 알 수 없는 제약과 명령만. 이력·상태·환경변수 표 없음 | 60 이하 |
| `src/main/java/com/fida/adapter/CLAUDE.md` | OCR 보정 규칙의 이유, Lazy 3단 설정 | 현재 38에서 약 25 |
| `deploy/server/README.md` | 서버 런북(유일한 배포 문서) | 약 110 |

환경변수의 기준 문서는 `.env.example` 하나로 한다(변수마다 주석 한 줄). `CLAUDE.md`의 표는 지운다.

### 루트 CLAUDE.md에서 지울 것

- `Current Status` 전체. 남길 것은 두 줄뿐이다: "정식 실행 경로는 OCI 상시 서버의 `FandingScheduler`", "KISTA 실패는 파이프라인 실패가 아님".
- `Task Management` 절과 shrimp 언급 3곳.
- `Environment Variables` 표(`.env.example`로 이동).
- `Design Reference`의 n8n 파리티 문단, 레포 밖 plan 경로, KISTA API 스펙 문단(코드 `FidaOrderRequest`가 기준).
- 전역 `~/.claude/CLAUDE.md`와 겹치는 `작업 방식`, `Git 규칙`의 push 항목.
- `02` 태스크 4를 실행했다면 job 프로필 관련 제약과 Commands.

남길 것: 코드 철학, 주석 규칙, 아키텍처 트리, Key Constraints 중 살아 있는 것(포트 7070, DB 금지, cron 변경 금지, 시트 셀 범위, `@Value` non-final, Mockito varargs, BOM), 커밋 author.

### 삭제 후보 (사용자 승인 필요)

| 대상 | 줄 | 근거 |
|---|---|---|
| `shrimp-rules.md` | 239 | 참조 1곳(`CLAUDE.md`), 내용 부패, 식별자 평문 노출 |
| `.shrimp-data/` | 480 | 2026-04-29 이후 미사용 |
| `docs/superpowers/plans/*` 4개, `specs/*` 4개 | 1,625 | 전부 구현 완료, 참조 0. git 이력에 남는다 |
| `docs/oci-migration-plan.md` | 105 | 이전 완료, 본문이 실제 배포 파일과 다름 |
| `domain/CLAUDE.md` | 29 | Port 표는 파일명으로 자명, 동기화 표는 `adapter/CLAUDE.md`와 중복 |
| `AGENTS.md` | 8 | 포인터뿐. Codex 등 다른 도구를 쓰면 유지 |
| 메모리 `feedback_commit_after_work.md` | 14 | 전역 CLAUDE.md와 완전 중복 |
| 메모리 `project_improvement_backlog.md` | 37 | 대부분 처리 완료, 남은 서술이 현재 코드와 다름(mem_limit, 스크롤 소요) |
| 메모리 `project_oci_migration_complete.md` | 15 | kista 서버 ssh 한 줄 외에는 중복. ssh는 `~/.ssh/config`에 이미 있다 |
| 로컬 `bin/` (비추적, 412K) | | IDE 컴파일 잔재 |

`shrimp-rules.md`의 Spreadsheet ID와 chat ID는 git 이력에 남는다. 둘 다 그 자체로 접근 권한을 주지 않는 식별자라 이력 재작성은 권하지 않는다. 레포가 공개인지는 확인하지 않았다. 공개라면 사용자가 판단한다.

### 기각한 대안

- **`docs/`를 `docs/archive/`로 옮기기.** 참조 없는 문서를 옮겨 두면 다음 정리 때 다시 판단해야 한다. git 이력으로 충분하다.
- **`playwright-server` 디렉토리 이름 변경.** 컨테이너 이름, 이미지 이름, compose, 워크플로, 서버의 실행 중 컨테이너까지 바꿔야 한다. 이름이 틀렸다는 사실을 `playwright-server/CLAUDE.md` 첫 줄에 적는 것으로 끝낸다.

## 예상 감량과 위험

- 추적 문서 2,359줄에서 약 330줄로(−86%). 자동 로드되는 루트 `CLAUDE.md`는 15KB에서 약 5KB로.
- 위험은 낮다. 코드 변경이 없다. `CLAUDE.md`에서 살아 있는 제약을 실수로 지우는 것이 유일한 위험이라, 태스크 1은 지울 줄과 남길 줄을 나눠 사용자에게 보여 준 뒤 적용한다.

## 태스크 분해

| # | 내용 | 대상 | 모델 | 완료 판정 |
|---|---|---|---|---|
| 1 | 루트 `CLAUDE.md` 재작성, `.env.example`에 변수별 주석 보강, `adapter/CLAUDE.md` 축소 | 세 파일 | **Opus** (무엇이 살아 있는 제약인지 판단) | `wc -l CLAUDE.md` 60 이하, `grep -c shrimp CLAUDE.md` 0 |
| 2 | 삭제 후보 삭제(승인된 것만) | 위 표 | Haiku | `git ls-files \| grep -E "shrimp\|docs/superpowers/(plans\|specs)\|oci-migration"` 결과 없음 |
| 3 | `deploy/server/README.md` 수정: 가드 시각 07:45, 수동 롤백에 `PLAYWRIGHT_SERVER_IMAGE` 추가, "최초 배포 후 스모크" 문단 삭제 | 1파일 | Haiku | `grep -n "07:30" deploy/server/README.md` 결과 없음 |
| 4 | `.gitignore` 정리: Querydsl·Fly.io·`application-prod.yml` 항목, `build/` 하위 중복, `*.env`와 `.env*` 중복 | 1파일 | Haiku | `git status --short` 변화 없음(무시 대상이 새로 드러나지 않음) |
| 5 | 배포 워크플로의 이미지 정리를 "현재와 직전 버전만 남기기"로 교체. 롤백이 `.prev-*-image`에 적힌 직전 이미지를 쓰므로 그것은 지우지 않는다 | `.github/workflows/server-deploy.yml` 헬스게이트 통과 뒤 단계 | Sonnet | 배포 1회 뒤 서버에서 `docker images \| grep -c fida` 가 4 이하 |
| 6 | 메모리 정리(승인된 것만), `MEMORY.md` 인덱스 갱신 | 메모리 디렉토리 | Haiku | 인덱스 항목 수와 파일 수 일치 |

fida가 kista-server로 옮겨 kista-infra `reconcile.sh`로 배포되면 5번의 워크플로 수정은 필요 없어진다. reconcile은 적용 성공 뒤 실행 중이 아닌 태그를 전부 지우고, 롤백 때는 GHCR에서 다시 받는다(fida 이미지 2개 익명 pull 확인됨, fable-infra 2026-11-12). 이전이 늦어질 때만 아래 수동 정리 1회를 한다.

### 이전 작업 (fida 쪽 몫, fable-infra 지침서 03 4-3절과 짝)

| # | 내용 | 대상 | 모델 | 완료 판정 |
|---|---|---|---|---|
| 7 | 배포 금지 창 훅 신설. kista-infra `reconcile.yml`이 앱 레포의 `deploy/hooks/pre-apply.sh`가 있으면 실행한다. 화~토 KST 06:50~07:45이면 exit 1 | `deploy/hooks/pre-apply.sh` (새 파일), `server-deploy.yml`의 가드 로직을 옮겨 옴 | Sonnet | `TZ=Asia/Seoul` 기준 시각을 인자로 받아 판정하는 자체 점검 1개(`bash deploy/hooks/pre-apply.sh --check "2026-11-12 07:10"` 등)가 금지·허용 양쪽을 맞힘 |
| 8 | 이전 완료 뒤 fida 자체 배포 정리: `server-deploy.yml`의 SSH 배포·헬스게이트·롤백 단계, `deploy/server/Caddyfile`, compose의 caddy 서비스 삭제. `KISTA_URL`을 `http://caddy:8080`으로. `deploy/server/README.md`를 새 구조에 맞게 축소 | 해당 파일들 | Sonnet | 이전 후 첫 07:00 실행에서 KISTA 전송 완료 로그와 heartbeat 핑 |

`/opt/fida/.fida-state/`는 옮기지 않고 새로 시작한다. Gemini quota 파일은 날짜별 카운트라 잃는 것은 이전 당일의 카운트뿐이다. 단 `03` 태스크 3(OCR 보관함 `/state/ocr-archive/`)을 이전 전에 배포했다면 그 디렉토리는 옮긴다. 새 디렉토리도 uid 100:101 소유여야 한다.

5번은 서버에 쌓인 22.9GB를 한 번 비우는 수동 정리(`docker image prune -a`에 실행 중·직전 이미지 제외)와 짝이다. 운영 서버에서 지우는 작업이므로 사용자가 직접 하거나 명시 승인 뒤에 한다.

## 하지 말아야 할 것

- `deploy/server/README.md`의 `chown` 절차와 `.env` 개행 경고(`cat -A`)를 지우지 않는다. 2026-08-04 사고 두 건의 유일한 기록이다.
- `feedback_unification.md` 메모리는 유지한다. 다른 문서에 없는 선호다.
- git 이력을 재작성하지 않는다.

## 범위 밖으로 남기는 질문

- **Google Sheets 기록이 아직 필요한가.** (2026-11-12 사용자: 일단 유지, 향후 삭제 고려) KISTA가 같은 값을 DB에 저장하고 admin 화면으로 보여 준다. 시트 경로는 Java 180줄, Google 의존 2개, 서비스 계정 시크릿, Lazy 3단 설정, uid 소유권 사고(2026-08-04)를 끌고 온다. n8n 시절의 유산이다. 사용자가 시트를 직접 보는지에 달렸으니 사용자가 정한다. 없애기로 하면 `SheetPort`, `GoogleSheetsAdapter`, `GoogleSheetsConfig`, `SheetException`, `secrets/` 마운트, `TradingRecordService`의 명시적 생성자(`@Lazy` 때문에 존재)가 함께 사라진다.

## 의존·순서

- 태스크 1은 `02` 태스크 4, `03` 태스크 6과 같은 파일(`CLAUDE.md`)을 건드린다. 코드 쪽 결정이 끝난 뒤 마지막에 한 번에 쓴다.
- 태스크 5는 독립이고 급하다(디스크). 먼저 해도 된다.
