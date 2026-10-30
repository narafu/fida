#!/usr/bin/env node
/**
 * scrape-server.js
 * scrape-fanding.js를 HTTP 서버로 감싸서 FIDA(Java)에서 호출할 수 있게 함.
 *
 * 실행: FANDING_EMAIL=... FANDING_PASSWORD=... node scrape-server.js
 * 포트: 3000
 * 엔드포인트: GET /scrape, GET /health
 */

const http = require('http');
const { execFile } = require('child_process');
const path = require('path');

const PORT = process.env.PORT || 3000;
const SCRIPT = path.join(__dirname, 'scrape-fanding.js');

// 스크래핑 동시 실행 방지 플래그 — Chromium 2개 동시 기동 시 free tier 메모리 초과 위험
let scraping = false;

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  if (req.method === 'GET' && req.url === '/scrape') {
    runScraper(res, '/scrape');
    return;
  }

  res.writeHead(404);
  res.end();
});

// 자식 프로세스를 비동기로 실행 — 이벤트 루프를 막지 않아 스크래핑 중에도 /health 응답 가능
function runScraper(res, label) {
  if (scraping) {
    console.warn(`[${new Date().toISOString()}] ${label} 거부 — 스크래핑 이미 진행 중`);
    respondJson(res, 503, { success: false, error: 'scrape already in progress' });
    return;
  }
  scraping = true;
  const start = Date.now();
  console.log(`[${new Date().toISOString()}] ${label} 요청 수신`);

  execFile('node', [SCRIPT], {
    env: process.env,
    maxBuffer: 100 * 1024 * 1024,
    timeout: 170000, // 정상 ~91s(1 vCPU 기준) 대비 여유. Java RestTemplate readTimeout(180s)보다 짧게 — 타임아웃 시 Java가 상세 오류 바디를 받도록
  }, (error, stdout, stderr) => {
    scraping = false;
    const elapsed = ((Date.now() - start) / 1000).toFixed(1);
    if (error) {
      const details = {
        error: error.message,
        status: error.code ?? null,
        signal: error.signal ?? null,
        stdout: tail(stdout),
        stderr: tail(stderr),
      };
      console.error(`[${new Date().toISOString()}] ${label} 실패 (${elapsed}s):`, JSON.stringify(details));
      respondJson(res, 500, { success: false, ...details });
      return;
    }
    console.log(`[${new Date().toISOString()}] ${label} 완료 (${elapsed}s)`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(stdout.toString());
  });
}

function respondJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function tail(value) {
  if (!value) return '';
  const text = Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
  return text.slice(-4000);
}

server.listen(PORT, () => {
  console.log(`Scrape server 시작: http://localhost:${PORT}`);
});
