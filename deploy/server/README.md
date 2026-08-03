# fida server deployment (OCI)

`fida`는 kista-api와는 **별도의 독립 OCI 인스턴스**에서 Docker Compose + Caddy로 운영한다. Playwright 헤드리스 브라우저·Gemini OCR의 튀는 리소스 부하가 매매 엔진(kista-api) 인스턴스와 자원 경합하지 않도록 격리하기 위함이다 — kista-api의 Caddy에 얹혀가는 구조(`shared_net` 결합)가 아니라, fida가 자기 소유의 Caddy·공인 IP·서브도메인(`fida.kista-app.com`)을 갖는다.

## 서버 레이아웃

```text
/opt/fida/
├── .env                              ← 서버에서 직접 관리 (Actions에서 덮어쓰지 않음)
├── Caddyfile                         ← GitHub Actions 업로드
├── docker-compose.yml                ← GitHub Actions 업로드
├── secrets/
│   └── service-account.json          ← 서버에서 직접 관리 (Actions가 만들지 않음)
└── .fida-state/                      ← 컨테이너 상태 볼륨 (Gemini quota usage 등)
```

## 초기 서버 설정 (최초 1회)

1. OCI 인스턴스(신규 생성): `VM.Standard.A1.Flex`(Ampere arm64), 1 OCPU, 6GB RAM, 부트 볼륨 50GB, Ubuntu 24.04 LTS — kista-api와 동일 VCN/서브넷에 위치하되 **별도 인스턴스·별도 예약 공인 IP**로 분리한다
2. 정적 공인 IP: 인스턴스 생성 시 Reserved Public IP 할당 → 도메인 A 레코드(`fida.kista-app.com`) 연결 — Cloudflare 등 프록시 지원 DNS는 반드시 "DNS only"(프록시 끔, 회색 구름)로 설정. 프록시를 켜면 Caddy의 Let's Encrypt 자동 인증서 발급(HTTP-01 challenge)이 실패한다
3. 인바운드 포트 개방 — 2단계 모두 확인 필요:
   - OCI 콘솔: 인스턴스가 속한 VCN의 Security List(또는 NSG)에 Ingress Rule 추가 — TCP `80`, `443`, `22`
   - OS 레벨 `iptables`(netfilter-persistent)도 별도 차단할 수 있음 — 막혀 있으면 규칙 추가 후 `sudo netfilter-persistent save`로 저장
   - `7070`(fida 내부 포트)은 비공개 유지 — Caddy만 80/443으로 외부 공개
4. Docker 설치:
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   ```
5. 배포 경로 생성:
   ```bash
   sudo mkdir -p /opt/fida/secrets /opt/fida/.fida-state
   sudo chown -R $USER:$USER /opt/fida
   vi /opt/fida/.env   # 아래 .env 내용 참고
   ```
6. 로그 로테이션 설정 (`/etc/docker/daemon.json`):
   ```json
   {
     "live-restore": true,
     "log-driver": "json-file",
     "log-opts": { "max-size": "50m", "max-file": "5" }
   }
   ```

## GitHub Secrets

| Secret | 설명 |
|--------|------|
| `SERVER_HOST` | fida 전용 서버 IP — kista-api 저장소의 동명 secret과 이름은 같지만 저장소가 달라 값은 별개 |
| `SERVER_USER` | SSH 사용자명 |
| `SERVER_SSH_KEY` | SSH 개인키 (PEM) |
| `SERVER_SSH_PORT` | SSH 포트 (기본값 22, 생략 가능) |

## .env 내용

```dotenv
GEMINI_API_KEY=

FANDING_EMAIL=
FANDING_PASSWORD=

GOOGLE_SHEETS_SPREADSHEET_ID=
GOOGLE_SERVICE_ACCOUNT_JSON_PATH=/secrets/service-account.json

TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
INTERNAL_API_TOKEN=

KISTA_URL=https://api.kista-app.com

HEARTBEAT_URL=   # healthchecks.io dead-man's switch (미설정 시 핑 생략)

FIDA_DOMAIN=fida.kista-app.com   # Caddyfile 치환용

FIDA_SCHEDULER_ENABLED=true      # 기본값이라 생략 가능이지만 명시 권장

GEMINI_QUOTA_USAGE_PATH=/state/gemini-quota-usage.json
```

`secrets/service-account.json`은 서버가 직접 관리한다(GitHub Actions가 만들지 않음) — 로컬 원본은 `/Users/phs/secret/google-sheet-secret.json`.

## 배포 흐름

1. `main` push → 테스트
2. arm64 이미지 2개(`fida`, `playwright-server`) 빌드 → GHCR push
3. fida 자체 스케줄 실행창(화~토 06:50~07:30 KST) 회피 후 SSH 배포
4. `docker compose pull && docker compose up -d --no-deps fida playwright-server` — routine 배포가 caddy를 건드리지 않는 kista-api와 동일한 blast-radius 격리 원칙. `docker compose up -d caddy`도 이어서 실행(이미지 태그 고정이라 기존 캐디는 무변경, 최초 배포 시에만 실제로 생성됨)
5. 헬스 게이트(최대 5분)
6. 실패 시 자동 롤백

**최초 배포 후 수동 스모크 권장**: `playwright-server/Dockerfile`이 Puppeteer 번들 Chrome 대신 시스템 Chromium(apt 설치, arm64 지원)을 쓰도록 전환됐다 — 번들 Chrome-for-Testing과 버전이 다르므로, 첫 배포 직후 실제 스크래핑 1회(수동 `workflow_dispatch` 또는 서버에서 직접 트리거)로 정상 동작을 확인할 것.

## 롤백 Runbook

**자동 롤백**: 헬스 게이트 실패 시 Actions가 이전 이미지로 자동 복구.

**수동 롤백**:
```bash
cd /opt/fida
docker images | grep fida

export FIDA_IMAGE=ghcr.io/<org>/fida:<previous-sha>
docker compose up -d --no-deps fida playwright-server
```
