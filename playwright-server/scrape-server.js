#!/usr/bin/env node
/**
 * scrape-server.js
 * scrape-fanding.js를 HTTP 서버로 감싸서 n8n(Docker)에서 호출할 수 있게 함.
 *
 * 실행: FANDING_EMAIL=... FANDING_PASSWORD=... node scrape-server.js
 * 포트: 3000
 * 엔드포인트: GET /scrape → scrape-fanding.js 실행 후 JSON 반환
 */

const http = require('http');
const { execFileSync } = require('child_process');
const path = require('path');

const PORT = process.env.PORT || 3000;
const SCRIPT = path.join(__dirname, 'scrape-fanding.js');

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/scrape') {
    const start = Date.now();
    console.log(`[${new Date().toISOString()}] /scrape 요청 수신`);
    try {
      const result = execFileSync('node', [SCRIPT], {
        env: { ...process.env },
        maxBuffer: 100 * 1024 * 1024,
        timeout: 120000,
      }).toString();
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(result);
      console.log(`[${new Date().toISOString()}] /scrape 완료 (${elapsed}s)`);
    } catch (e) {
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      const details = buildChildError(e);
      const errJson = JSON.stringify({ success: false, ...details });
      console.error(`[${new Date().toISOString()}] /scrape 실패 (${elapsed}s):`, JSON.stringify(details));
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(errJson);
    }
  } else if (req.method === 'GET' && req.url.startsWith('/scrape-url')) {
    // 특정 fanding 상세 페이지 URL을 직접 지정해 스크래핑
    const targetUrl = new URL(req.url, 'http://localhost').searchParams.get('url');
    if (!targetUrl) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: 'url 파라미터 필요' }));
      return;
    }
    // SSRF 방지: fanding.kr의 https URL만 허용
    try {
      const u = new URL(targetUrl);
      const host = u.hostname.toLowerCase().replace(/\.$/, '');
      if (u.protocol !== 'https:' || host !== 'fanding.kr') {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'host not allowed: fanding.kr only' }));
        return;
      }
    } catch (_) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: 'invalid url format' }));
      return;
    }
    const start = Date.now();
    console.log(`[${new Date().toISOString()}] /scrape-url 요청 수신: ${targetUrl}`);
    try {
      const result = execFileSync('node', [SCRIPT], {
        env: { ...process.env, TARGET_URL: targetUrl },
        maxBuffer: 100 * 1024 * 1024,
        timeout: 120000,
      }).toString();
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(result);
      console.log(`[${new Date().toISOString()}] /scrape-url 완료 (${elapsed}s)`);
    } catch (e) {
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      const details = buildChildError(e);
      const errJson = JSON.stringify({ success: false, ...details });
      console.error(`[${new Date().toISOString()}] /scrape-url 실패 (${elapsed}s):`, JSON.stringify(details));
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(errJson);
    }
  } else if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
  } else {
    res.writeHead(404);
    res.end();
  }
});

server.listen(PORT, () => {
  console.log(`Scrape server 시작: http://localhost:${PORT}`);
  console.log(`n8n에서 호출 URL: http://host.docker.internal:${PORT}/scrape`);
});

function buildChildError(error) {
  return {
    error: error.message,
    status: error.status ?? null,
    signal: error.signal ?? null,
    stdout: tail(error.stdout),
    stderr: tail(error.stderr),
  };
}

function tail(value) {
  if (!value) return '';
  const text = Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
  return text.slice(-4000);
}
