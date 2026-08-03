FROM node:20-slim

# Install system libraries that Chrome for Testing depends on
RUN apt-get update && apt-get install -y \
    chromium \
    --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Chrome for Testing(Puppeteer 기본 다운로드)은 Linux arm64 빌드를 제공하지 않는다
# (https://github.com/puppeteer/puppeteer/issues/7740) — 시스템 Chromium을 직접 사용
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

EXPOSE 3000

CMD ["node", "scrape-server.js"]
