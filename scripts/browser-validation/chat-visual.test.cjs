const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { test, before, after } = require('node:test');
const { chromium } = require('playwright');
const fixtures = require('./chat-fixtures.cjs');

// CHAT_VISUAL_DIST: 다른 빌드(예: 원래 화면 기준선)를 측정할 때 쓰는 dist 경로. 기본은 현재 web-ui/dist.
// CHAT_VISUAL_METRICS: 지표 파일 이름(기본 metrics.json). 사용자 지정 dist의 스크린샷은 out/custom-dist/에 저장한다.
const customDist = !!process.env.CHAT_VISUAL_DIST;
const dist = path.resolve(process.env.CHAT_VISUAL_DIST || path.join(__dirname, '../../web-ui/dist'));
const out = path.join(__dirname, 'out');
const reducedMotion = process.env.CHAT_VISUAL_REDUCED_MOTION === '1' ? 'reduce' : 'no-preference';
const shotDir = path.join(out, customDist ? 'custom-dist' : '', reducedMotion === 'reduce' ? 'reduced-motion' : '');
const metricsFile = process.env.CHAT_VISUAL_METRICS || (reducedMotion === 'reduce' ? 'metrics.reduced-motion.json' : customDist ? 'metrics.custom-dist.json' : 'metrics.json');
const widths = [[360, 800], [393, 852], [412, 915]];
const SNAPSHOT_EVENT = 'ziphap:snapshot'; // web-ui/src/bridge.ts
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };

// 기존 화면의 알려진 대비 부족. 컨트롤러 결정 전까지 허용 목록으로 기록한다.
// 항목 형식: `${fixture}|${selector}` (selector는 요소의 tag.class).
const KNOWN_CONTRAST_BASELINE = new Set([
  // 현재 기준선에서는 대비 부족이 없어 비어 있다.
]);

let server, browser, base;
const metrics = [];

before(async () => {
  fs.mkdirSync(shotDir, { recursive: true });
  server = http.createServer((req, res) => {
    const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.join(dist, rel);
    if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}/`;
  const channel = process.env.CHAT_VISUAL_CHANNEL;
  browser = await chromium.launch({ headless: true, ...(channel ? { channel } : {}) });
});

after(async () => {
  fs.writeFileSync(path.join(out, metricsFile), JSON.stringify(metrics, null, 2));
  await browser?.close();
  server?.close();
});

function measure() {
  const parse = c => { const m = c.match(/rgba?\(([^)]+)\)/); const p = m[1].split(/[ ,\/]+/).map(Number); return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; };
  const over = (top, bot) => { const a = top[3]; return [0, 1, 2].map(i => top[i] * a + bot[i] * (1 - a)).concat(1); };
  const lum = ([r, g, b]) => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const pageBg = [247, 248, 252, 1];
  const bgOf = el => {
    const chain = [];
    for (let e = el; e; e = e.parentElement) {
      const c = parse(getComputedStyle(e).backgroundColor);
      if (c[3] > 0) { chain.push(c); if (c[3] === 1) break; }
    }
    return chain.reduceRight((bot, c) => over(c, bot), pageBg);
  };
  const visible = el => {
    for (let e = el; e; e = e.parentElement) {
      if (e.getAttribute('aria-hidden') === 'true') return false;
      const s = getComputedStyle(e);
      if (s.display === 'none' || s.visibility === 'hidden') return false;
    }
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const sel = el => el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/)[0] : '');
  let minContrast = Infinity; const failures = [];
  for (const el of document.body.querySelectorAll('*')) {
    if (![...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim())) continue;
    if (!visible(el) || el.closest('button:disabled,[disabled],fieldset:disabled')) continue;
    const s = getComputedStyle(el);
    const bg = bgOf(el);
    const fg = over(parse(s.color), bg);
    const c = ratio(fg, bg);
    const size = parseFloat(s.fontSize), bold = parseInt(s.fontWeight, 10) >= 700;
    const need = size >= 24 || (bold && size >= 18.66) ? 3 : 4.5;
    minContrast = Math.min(minContrast, c);
    if (c < need) failures.push({ selector: sel(el), contrast: +c.toFixed(2), need });
  }
  // 가시 항목: 스크롤 컨테이너(main.conversation) 안에서 센다.
  // 세는 규칙: 항목 높이의 50% 이상이 box 안에 보이거나, 보이는 높이가 120px 이상.
  // 가장 바깥 카드만 센다(중첩 제외, .website-input-slot 제외, 숨은 요소 제외).
  const CARD = '.message, .work-card, .task-card';
  const main = document.querySelector('main.conversation');
  if (!main) throw new Error('main.conversation missing');
  const box = main.getBoundingClientRect();
  const outer = [...document.querySelectorAll(CARD)].filter(el =>
    !el.classList.contains('website-input-slot') && !el.parentElement?.closest(CARD) && visible(el));
  const visibleItems = outer.filter(el => {
    const r = el.getBoundingClientRect();
    if (!(r.bottom > box.top && r.top < box.bottom)) return false;
    const shown = Math.min(r.bottom, box.bottom) - Math.max(r.top, box.top);
    return shown >= 0.5 * r.height || shown >= 120;
  }).length;
  const renderedMessages = outer.filter(el => el.classList.contains('message')).length;
  const renderedCards = outer.length - renderedMessages;
  const heights = outer.filter(el => !el.classList.contains('message')).map(el => el.getBoundingClientRect().height).sort((a, b) => a - b);
  const medianCardHeight = heights.length === 0 ? null
    : +(heights.length % 2 ? heights[(heights.length - 1) / 2] : (heights[heights.length / 2 - 1] + heights[heights.length / 2]) / 2).toFixed(1);
  // 가로 넘침: 문서와 .conversation 모두. 명시적 가로 스크롤 래퍼(overflow-x auto/scroll, 너비 안) 안쪽 후손은 허용.
  const insideScroller = el => {
    for (let e = el.parentElement; e && e !== main; e = e.parentElement) {
      const o = getComputedStyle(e).overflowX;
      if ((o === 'auto' || o === 'scroll') && e.getBoundingClientRect().right <= innerWidth + 1) return true;
    }
    return false;
  };
  const overflowing = [...main.querySelectorAll('*')]
    .filter(el => visible(el) && el.getBoundingClientRect().right > innerWidth + 1 && !insideScroller(el)).map(sel).slice(0, 5);
  return {
    visibleItems, medianCardHeight, renderedMessages, renderedCards, loading: !!document.querySelector('.loading'),
    welcome: !!document.querySelector('.welcome'),
    docScrollWidth: document.documentElement.scrollWidth, docOverflow: document.documentElement.scrollWidth > innerWidth,
    convScrollWidth: main.scrollWidth, convClientWidth: main.clientWidth,
    convOverflow: main.scrollWidth > main.clientWidth + 1, overflowing,
    minContrast: minContrast === Infinity ? null : +minContrast.toFixed(2), failures,
  };
}

// expect: 렌더되어야 하는 .message 수와 가장 바깥 카드 수. 첫 실행 결과를 스크린샷으로 확인해 정한 값.
const expectations = {
  empty: { messages: 0, cards: 0 }, chatShort: { messages: 2, cards: 0 }, chatLong: { messages: 20, cards: 0 },
  taskRunning: { messages: 1, cards: 1 }, taskPaused: { messages: 1, cards: 1 }, taskWaitingGoal: { messages: 1, cards: 1 },
  taskFailed: { messages: 1, cards: 1 }, taskCompleted: { messages: 2, cards: 1 }, unanchoredTasks: { messages: 1, cards: 3 },
  mixedLong: { messages: 20, cards: 10 }, twoLiveTasks: { messages: 1, cards: 2 }, websiteCard: { messages: 1, cards: 2 }, files: { messages: 2, cards: 0 },
  camping: { messages: 4, cards: 1 },
  choices: { messages: 2, cards: 0 }, approval: { messages: 1, cards: 1 },
  guide: { messages: 0, cards: 0 },
};

for (const [name, snapshot] of Object.entries(fixtures)) {
  for (const [width, height] of widths) {
    test(`${name} @${width}`, async () => {
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1, reducedMotion });
      try {
        await page.addInitScript(() => { window.ziphapNative = { postMessage() {} }; });
        await page.goto(base);
        await page.waitForSelector('#root > *');
        const want = expectations[name];
        assert.ok(want, `expectation missing for ${name}`);
        // 앱이 듣기 시작할 때까지 50ms마다 스냅숏을 다시 보낸다(최대 5초).
        await page.waitForFunction(([event, detail, wantMessages]) => {
          window.dispatchEvent(new CustomEvent(event, { detail }));
          return !document.querySelector('.loading') && document.querySelectorAll('.message').length === wantMessages;
        }, [SNAPSHOT_EVENT, snapshot, want.messages], { polling: 50, timeout: 5000 });
        await page.waitForFunction(cards => document.querySelectorAll('.task-card, .work-card').length >= cards, want.cards, { timeout: 5000 });
        if (name === 'camping') {
          await page.locator('.stay-top').click();
          assert.equal(await page.getByRole('link', { name: '전체 배치도' }).getAttribute('href'), 'https://example.org/map.png');
          assert.equal(await page.getByText('결제 기한', { exact: true }).count(), 1);
          assert.equal(await page.getByRole('button', { name: '카드결제 준비' }).count(), 1);
          assert.equal(await page.getByRole('button', { name: '토스페이 준비' }).count(), 0);
          await page.getByRole('button', { name: '채팅에서 배치도 보기' }).click();
          await page.evaluate(() => {
            const canvas = document.createElement('canvas');
            canvas.width = 400; canvas.height = 220;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 400, 220);
            ctx.fillStyle = '#1a1a1a'; ctx.fillText('A-04', 150, 145);
            window.dispatchEvent(new CustomEvent('ziphap:stayPhoto', {detail: {
              kind: 'map', messageId: 'camp3', index: 0, imageUrl: 'https://example.org/map.png',
              src: canvas.toDataURL('image/jpeg'), pins: [{site: 'A-04', x: 40, y: 65}],
            }}));
          });
          await page.getByRole('button', { name: '배치도 확대' }).click();
          await page.getByRole('button', { name: '빈자리 A-04' }).click();
          assert.equal(await page.getByText('A-04 선택').count(), 1);
          assert.equal(await page.getByRole('button', { name: '빈자리 A-12' }).count(), 0);
          assert.equal(await page.getByRole('button', { name: 'A-12', exact: true }).count(), 1);
        }
        await page.evaluate(async () => {
          await document.fonts.ready;
          await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
          // 등장 애니메이션(유한)은 끝난 모습으로 찍는다. 반복 애니메이션은 그대로 둔다.
          for (const animation of document.getAnimations()) {
            if (animation.effect?.getComputedTiming().iterations !== Infinity) animation.finish();
          }
          await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        });
        await page.screenshot({ path: path.join(shotDir, `${name}-${width}.png`) });
        const m = await page.evaluate(measure);
        metrics.push({
          fixture: name, width, visibleItems: m.visibleItems, medianCardHeight: m.medianCardHeight, overflowX: m.docOverflow || m.convOverflow,
          docScrollWidth: m.docScrollWidth, convScrollWidth: m.convScrollWidth, convClientWidth: m.convClientWidth,
          renderedMessages: m.renderedMessages, renderedCards: m.renderedCards, minContrast: m.minContrast, contrastFailures: m.failures,
        });
        assert.equal(m.loading, false, 'still loading');
        assert.equal(m.renderedMessages, want.messages, 'rendered .message count');
        assert.equal(m.renderedCards, want.cards, 'rendered outermost card count');
        if (name === 'empty') assert.equal(m.welcome, true, 'welcome rendered');
        assert.equal(m.docOverflow, false, 'document horizontal overflow');
        assert.deepEqual(m.convOverflow ? m.overflowing : [], [], '.conversation horizontal overflow (outside explicit scroll wrappers)');
        const unexpected = m.failures.filter(f => !KNOWN_CONTRAST_BASELINE.has(`${name}|${f.selector}`));
        assert.deepEqual(unexpected, [], 'contrast below threshold');
      } finally { await page.close(); }
    });
  }
}
