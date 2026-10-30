FROM node:20-slim

# Install system libraries that Chrome for Testing depends on
RUN apt-get update && apt-get install -y \
    chromium \
    fontconfig \
    --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# 폰트 캐시를 root로 미리 생성 — 비root(node)는 /var/cache/fontconfig에 쓸 수 없어 캐시 없이 매 조회마다
# 폰트를 재스캔하고, 1 vCPU 서버에서 Chromium 렌더링이 멈춰 스크래핑이 타임아웃됨(2026-10-31 운영 장애)
RUN fc-cache -f

# Chrome for Testing(Puppeteer 기본 다운로드)은 Linux arm64 빌드를 제공하지 않는다
# (https://github.com/puppeteer/puppeteer/issues/7740) — 시스템 Chromium을 직접 사용
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

# Chromium을 root로 돌리지 않도록 node 이미지 기본 비특권 사용자로 실행
USER node

EXPOSE 3000

CMD ["node", "scrape-server.js"]
