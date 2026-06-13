# playwright-server

Node.js 사이드카 — Java로 이식 금지.

## Commands

```bash
node scrape-server.js   # HTTP 서버 기동 (포트 3000)
node scrape-fanding.js  # 스크래퍼 직접 실행 (디버그용)
```

## API

- `GET /health` — 헬스체크 (200 OK)
- `GET /scrape` — fanding.kr 최신 게시물 스크래핑. 응답: `{ postDate, title, imageUrl }`
- `GET /scrape-url?url=<fanding_url>` — 특정 상세 페이지 URL 스크래핑. SSRF 방어: `fanding.kr` HTTPS URL만 허용

## Environment Variables

- `FANDING_EMAIL` / `FANDING_PASSWORD` — fanding.kr 로그인 (필수)

## Gotchas

- `node:20-slim`에 `wget`/`curl` 없음 → healthcheck는 `["CMD","node","-e","require('http').get('http://localhost:3000/health',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"]`
- `npm ci`는 `package-lock.json` 필수 → 없으면 `playwright-server/`에서 `npm install --package-lock-only`로 생성
- `.gitignore`의 `.env*` 패턴이 `.env.example`도 차단 → `!.env.example` 예외 필요
