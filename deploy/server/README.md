# fida server deployment (kista-server)

`fida`는 kista-server(OCI)에서 kista-infra의 `reconcile.sh`로 배포된다. 리버스 프록시·TLS는 kista-infra Caddy가 맡고, fida는 `shared_net`으로만 붙는다. 이 디렉토리는 infra가 읽는 배포 bundle이다.

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | 서비스 정의. `-p fida --project-directory /opt/fida`로 실행된다 |
| `roles` | 적용 순서. `playwright-server` → `fida` |
| `images` | role별 이미지 repo |
| `caddy/fida.caddy` | 도메인 라우팅. `/actuator/health`만 공개, 나머지 404 |
| `../hooks/pre-apply.sh` | 배포 금지 창(화~토 06:55~07:40 KST) 검사. infra reconcile.yml이 실행한다 |

네트워크: fida는 `shared_net`(Caddy)과 `browser_net`(playwright 전용), playwright-server는 `browser_net`만. 둘 다 `data_net`(postgres/redis)에는 들어가지 않는다.

## 서버 레이아웃

```text
/opt/fida/
├── .env                              ← kista-infra가 렌더링 (직접 편집 금지)
├── releases/<id>/, current           ← kista-infra가 bundle 업로드
├── secrets/
│   └── service-account.json          ← 서버에 파일로 직접 둔다 (멀티라인이라 .env에 못 넣음)
└── .fida-state/                      ← 컨테이너 상태 볼륨 (Gemini quota usage, ocr-archive/)
```

## .env 내용

`/opt/fida/.env`는 kista-infra `secrets/fida.env`(sops)가 배포마다 렌더링한다. 서버에서 직접 편집하지 말고 infra 레포에서 바꾼다. compose가 `FANDING_EMAIL`·`FANDING_PASSWORD`를 이 파일에서 보간해 playwright-server에 넘긴다.

`.env` 반영 후 각 줄이 개행으로 분리됐는지 `cat -A /opt/fida/.env`로 확인할 것 — 붙여넣기 방식에 따라 개행이 누락되면 두 변수가 한 줄로 합쳐져(예: `INTERNAL_API_TOKEN=...KISTA_URL=...`) 뒤 변수가 통째로 앞 변수 값에 흡수되고, 애플리케이션은 조용히 `application.yml` 기본값으로 폴백한다(에러 로그 없이 발생하는 실패라 발견이 어려움)

```dotenv
GEMINI_API_KEY=

FANDING_EMAIL=
FANDING_PASSWORD=

GOOGLE_SHEETS_SPREADSHEET_ID=
GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/secrets/service-account.json

TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
INTERNAL_API_TOKEN=

KISTA_URL=https://api.kista-app.com   # 리스너 배포 뒤 infra가 http://caddy:8080으로 전환

HEARTBEAT_URL=   # healthchecks.io dead-man's switch (미설정 시 핑 생략)

FIDA_SCHEDULER_ENABLED=true      # 기본값이라 생략 가능이지만 명시 권장

GEMINI_QUOTA_USAGE_PATH=/state/gemini-quota-usage.json
```

healthchecks.io 체크: slug `fida-scheduler`, schedule `0 7 * * 2-6`(tz `Asia/Seoul`), grace 1시간. 성공한 스케줄 실행 직후에만 핑한다.

`secrets/service-account.json`은 서버가 직접 관리한다(배포가 만들지 않음) — 로컬 원본은 `/Users/phs/secret/google-sheet-secret.json`.

`fida` 컨테이너는 non-root(`appuser`, Alpine `adduser -S`가 부여하는 UID/GID — 통상 100:101이나 고정 보장은 없음)로 실행되므로, 파일을 배치한 뒤 컨테이너 내부 UID/GID로 소유권을 맞춰야 한다(기본 `600`에 소유자가 다르면 `Permission denied`로 부팅 실패):
```bash
docker exec fida id                                    # 예: uid=100(appuser) gid=101(appgroup)
sudo chown 100:101 /opt/fida/secrets/service-account.json
sudo chmod 600 /opt/fida/secrets/service-account.json
```

`.fida-state/`(Gemini quota 사용량, OCR 보관함 `ocr-archive/`)도 동일한 이유로 소유권을 맞춰야 한다 — `mkdir -p`로 생성 시 기본 소유자(root 또는 배포 계정)가 appuser와 달라 쓰기 실패 시 예외를 삼키고 조용히 무시되므로(2026-08 사례: 8/3부터 3주간 quota 카운트가 매번 리셋됨) 최초 배포 시 반드시 확인:
```bash
sudo mkdir -p /opt/fida/secrets /opt/fida/.fida-state
sudo chown -R 100:101 /opt/fida/.fida-state
```

## 배포 흐름

1. `main` push → 테스트
2. arm64 이미지 2개(`fida`, `fida-playwright-server`) 빌드 → GHCR push (SHA 태그)
3. kista-infra로 `repository_dispatch(deploy)` — `roles=playwright-server=<sha> fida=<sha>`
4. infra reconcile.yml: pre-apply hook(배포 금지 창) → bundle 업로드 → `reconcile.sh fida <id>` (role별 stop-first 교체·헬스 게이트, 실패 시 이전 release로 자동 롤백) → Caddy 스니펫 설치·reload
5. 결과는 이 레포 커밋의 status `deploy/production`

긴급 배포는 Actions `Server Deploy` workflow_dispatch에서 `force=true`(pre-apply hook 생략).

## 수동 트리거

Caddy는 `/actuator/health`만 외부에 공개하고 나머지 경로는 404를 반환한다(주문 트리거·swagger 무인증 노출 차단). 수동 실행은 서버에서 컨테이너 내부로 호출한다:

```bash
# 전체 파이프라인
docker exec fida wget -qO- --post-data= http://localhost:7070/api/fida/orders
# swagger 등 브라우저 접근이 필요하면 SSH 터널 (fida는 호스트 포트를 열지 않으므로 shared_net 컨테이너 IP 경유)
ssh -L 7070:$(ssh <server> "docker inspect -f '{{(index .NetworkSettings.Networks \"shared_net\").IPAddress}}' fida"):7070 <server>
```

## 롤백

자동 롤백은 reconcile.sh가 한다. 특정 이전 커밋으로 되돌리려면 kista-infra Actions `Reconcile App`을 수동 실행한다(app `fida`, config·roles에 이전 SHA. roles 형식은 `playwright-server=<sha> fida=<sha>`).
