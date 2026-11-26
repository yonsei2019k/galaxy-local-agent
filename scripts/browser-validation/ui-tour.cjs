// 고정 자료만으로는 열리지 않는 화면(사이드바, 작업 기록 시트, 음성 대화, 숙소 비교 위쪽)을 393px로 찍는다.
// 사용법: node scripts/browser-validation/ui-tour.cjs  (먼저 web-ui에서 npm run build). 결과: out/tour-*.png
// CHAT_VISUAL_REDUCED_MOTION=1 이면 축소 모션으로 찍는다. 검사(assert)는 chat-visual.test.cjs가 맡는다.
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./chat-fixtures.cjs');

const dist = path.join(__dirname, '../../web-ui/dist');
const out = path.join(__dirname, 'out');
const reducedMotion = process.env.CHAT_VISUAL_REDUCED_MOTION === '1' ? 'reduce' : 'no-preference';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

async function main() {
  fs.mkdirSync(out, { recursive: true });
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.join(dist, rel);
    if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch({ headless: true });
  const shot = async (name, snapshot, act) => {
    const page = await browser.newPage({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 1, reducedMotion });
    await page.addInitScript(() => { window.ziphapNative = { postMessage() {} }; });
    await page.goto(base);
    await page.waitForSelector('#root > *');
    await page.waitForFunction(([detail]) => {
      window.dispatchEvent(new CustomEvent('ziphap:snapshot', { detail }));
      return !document.querySelector('.loading') && !!document.querySelector('main.conversation');
    }, [snapshot], { polling: 50, timeout: 5000 });
    if (act) await act(page);
    await page.evaluate(async () => {
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      for (const a of document.getAnimations()) if (a.effect?.getComputedTiming().iterations !== Infinity) a.finish();
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    });
    const suffix = reducedMotion === 'reduce' ? '-reduced' : '';
    await page.screenshot({ path: path.join(out, `tour-${name}${suffix}.png`) });
    await page.close();
    console.log(`tour-${name}${suffix}.png`);
  };
  const stays = fixtures.camping;
  await shot('stays', stays, page => page.evaluate(() => { document.querySelector('main.conversation').scrollTop = 0; }));
  await shot('stays-open', stays, async page => {
    await page.locator('.stay-top').first().click();
    await page.evaluate(() => document.querySelector('.stay-comparison').scrollIntoView({ block: 'start' }));
  });
  await shot('sidebar', fixtures.mixedLong, page => page.getByRole('button', { name: '대화 및 앱 메뉴 열기' }).click());
  await shot('work-history', fixtures.mixedLong, page => page.locator('.work-card').last().click());
  await shot('work-detail', fixtures.mixedLong, async page => {
    await page.locator('.work-card').last().click();
    await page.locator('.work-history-row').first().click();
  });
  await shot('voice', { ...fixtures.chatShort, voiceTalk: { stage: 'listening', transcript: '내일 오전 일정 알려 줘', reply: '', blocked: false } });
  await shot('character', fixtures.chatShort, page => page.getByRole('button', { name: '집순이 모습 바꾸기' }).click());
  await shot('loading', { ...fixtures.empty, ready: false }, null).catch(() => console.log('loading view skipped (snapshot not ready never renders main)'));
  await browser.close();
  server.close();
}

main().catch(error => { console.error(error); process.exit(1); });
