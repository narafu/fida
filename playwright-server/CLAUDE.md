# playwright-server

Node.js 사이드카 — Java로 이식 금지.

## Gotchas

- `node:20-slim`에 `wget`/`curl` 없음 → healthcheck는 `["CMD","node","-e","require('http').get('http://localhost:3000/health',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"]`
- `npm ci`는 `package-lock.json` 필수 → 없으면 `playwright-server/`에서 `npm install --package-lock-only`로 생성
- `.gitignore`의 `.env*` 패턴이 `.env.example`도 차단 → `!.env.example` 예외 필요
