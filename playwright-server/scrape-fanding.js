#!/usr/bin/env node
/**
 * scrape-fanding.js
 * fanding.kr에서 라오어 Privacy 시리즈 최신글의 이미지 URL을 추출합니다.
 * n8n Execute Command 노드에서 호출하며, 결과를 JSON으로 stdout 출력합니다.
 *
 * 환경변수:
 *   FANDING_EMAIL    - 로그인 이메일
 *   FANDING_PASSWORD - 로그인 비밀번호
 *
 * 출력 JSON:
 *   { success: true, postDate: "YYYY-MM-DD", postUrl: "...", imageUrls: [...], cookies: [...] }
 *   { success: false, error: "..." }
 */

const puppeteer = require('puppeteer');

const EMAIL = process.env.FANDING_EMAIL;
const PASSWORD = process.env.FANDING_PASSWORD;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!EMAIL || !PASSWORD) {
  console.log(JSON.stringify({ success: false, error: 'FANDING_EMAIL or FANDING_PASSWORD not set' }));
  process.exit(1);
}

(async () => {
  let browser;
  try {
    browser = await puppeteer.launch({
      headless: 'new',
      args: ['--no-sandbox', '--disable-setuid-sandbox'],
    });
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });

    // ── STEP 1: 로그인 ──────────────────────────────────────────────
    await page.goto('https://fanding.kr', { waitUntil: 'networkidle2' });
    await page.click('button.side-nav__login');
    await page.waitForSelector('input.fd-text-input__core[type="email"]');
    await page.type('input.fd-text-input__core[type="email"]', EMAIL);
    await page.type('input.fd-text-input__core[type="password"]', PASSWORD);
    await page.click('button[type="submit"].v-button');
    await sleep(3000);

    const stillLoggedOut = await page.$('button.side-nav__login');
    if (stillLoggedOut) {
      throw new Error('로그인 실패: 자격증명을 확인하세요.');
    }

    // ── STEP 2: Privacy 시리즈 직접 접근 + 스크롤로 전체 로드 ────────
    await page.goto('https://fanding.kr/@laofus/series/1933/', { waitUntil: 'networkidle2' });
    await sleep(1000);

    // 무한스크롤: 새 링크가 더 이상 늘지 않을 때까지 스크롤
    let prevCount = 0;
    for (let i = 0; i < 30; i++) {
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await sleep(700);
      const count = await page.evaluate(() =>
        new Set(Array.from(document.querySelectorAll('a[href*="/@laofus/post/"]')).map(a => a.href)).size
      );
      if (count === prevCount) break;
      prevCount = count;
    }

    // ── STEP 3: 최신글 URL 추출 (가장 높은 post ID = 최신) ──────────
    const latestPostUrl = await page.evaluate(() => {
      const links = [...new Set(
        Array.from(document.querySelectorAll('a[href*="/@laofus/post/"]')).map(a => a.href)
      )];
      // post ID 숫자 기준 내림차순 정렬 → 첫 번째가 최신
      links.sort((a, b) => {
        const idA = parseInt(a.match(/\/post\/(\d+)/)?.[1] || '0');
        const idB = parseInt(b.match(/\/post\/(\d+)/)?.[1] || '0');
        return idB - idA;
      });
      return links[0] || null;
    });

    if (!latestPostUrl) {
      throw new Error('라오어 Privacy 최신 게시글을 찾을 수 없습니다.');
    }

    // ── STEP 4: 게시글 접속 → 이미지 URL 수집 ─────────────────────
    await page.goto(latestPostUrl, { waitUntil: 'networkidle2' });
    await sleep(1000);

    // 게시글 제목 추출
    const postTitle = await page.evaluate(() => {
      const h1 = document.querySelector('h1');
      if (h1 && h1.textContent.trim()) return h1.textContent.trim();
      return document.title || '';
    });

    // 게시글 날짜 추출
    const postDate = await page.evaluate(() => {
      const dateEl = document.querySelector('time, [class*="date"], [class*="Date"]');
      if (!dateEl) return new Date().toISOString().split('T')[0];
      const raw = dateEl.getAttribute('datetime') || dateEl.textContent.trim();
      const match = raw.match(/(\d{4})[.\-\/](\d{1,2})[.\-\/](\d{1,2})/);
      if (match) {
        return `${match[1]}-${match[2].padStart(2, '0')}-${match[3].padStart(2, '0')}`;
      }
      return new Date().toISOString().split('T')[0];
    });

    // 본문 이미지 URL 수집 (selector: img.fd-editor-image, 부모: tiptap ProseMirror)
    const imageUrls = await page.evaluate(() => {
      const imgs = Array.from(document.querySelectorAll('img.fd-editor-image'));
      if (imgs.length > 0) {
        return imgs.map((img) => img.src).filter(Boolean);
      }
      // 폴백: ProseMirror 컨테이너 내 모든 img
      const editor = document.querySelector('.tiptap.ProseMirror, .fd-editor__content');
      if (editor) {
        return Array.from(editor.querySelectorAll('img'))
          .map((img) => img.src)
          .filter(Boolean);
      }
      return [];
    });

    if (imageUrls.length === 0) {
      throw new Error('게시글 본문에서 이미지를 찾을 수 없습니다.');
    }

    // ── STEP 4.5: 팝업/모달 닫기 ───────────────────────────────────
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const closeBtn = btns.find((b) => b.textContent.trim() === '닫기');
      if (closeBtn) closeBtn.click();
    });
    await sleep(800);

    // ── STEP 5: 이미지 요소 스크린샷 (CDN 인증 우회) ───────────────
    // page.goto로 이미지 URL 직접 접근 시 CDN이 차단하므로
    // 렌더링된 img 요소를 Puppeteer로 직접 스크린샷
    const imgHandles = await page.$$('img.fd-editor-image');
    const images = [];
    for (const handle of imgHandles) {
      const buffer = await handle.screenshot({ type: 'png' });
      images.push({ base64: buffer.toString('base64'), mimeType: 'image/png' });
    }

    if (images.length === 0) {
      throw new Error('이미지 스크린샷 실패');
    }

    await browser.close();

    console.log(JSON.stringify({ success: true, postTitle, postDate, postUrl: latestPostUrl, images }));
  } catch (err) {
    if (browser) await browser.close();
    console.log(JSON.stringify({ success: false, error: err.message }));
    process.exit(1);
  }
})();
