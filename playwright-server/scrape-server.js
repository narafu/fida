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
const { execSync } = require('child_process');
const path = require('path');

const PORT = process.env.PORT || 3000;
const SCRIPT = path.join(__dirname, 'scrape-fanding.js');

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/scrape') {
    console.log(`[${new Date().toISOString()}] /scrape 요청 수신`);
    try {
      const result = execSync(`node ${SCRIPT}`, {
        env: { ...process.env },
        maxBuffer: 100 * 1024 * 1024,
        timeout: 120000,
      }).toString();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(result);
      console.log(`[${new Date().toISOString()}] /scrape 완료`);
    } catch (e) {
      const errJson = JSON.stringify({ success: false, error: e.message });
      console.error(`[${new Date().toISOString()}] /scrape 오류:`, e.message);
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
