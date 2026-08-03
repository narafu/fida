FROM node:20-slim

# Install system libraries that Chrome for Testing depends on
RUN apt-get update && apt-get install -y \
    chromium \
    --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=false: puppeteer downloads its own Chrome for Testing
# Do NOT set PUPPETEER_EXECUTABLE_PATH — use puppeteer bundled Chrome, not system Debian chromium
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=false

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

EXPOSE 3000

CMD ["node", "scrape-server.js"]
