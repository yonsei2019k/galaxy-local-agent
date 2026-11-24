const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { test, before, after } = require('node:test');
const { chromium } = require('playwright');
const fixtures = require('./chat-fixtures.cjs');

const dist = path.join(__dirname, '../../web-ui/dist');
const out = path.join(__dirname, 'out');
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
  fs.mkdirSync(out, { recursive: true });
  server = http.createServer((req, res) => {
    const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.join(dist, rel);
    if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}/`;
  browser = await chromium.launch({ headless: true });
});

after(async () => {
  fs.writeFileSync(path.join(out, 'metrics.json'), JSON.stringify(metrics, null, 2));
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
  const vh = innerHeight;
  const visibleItems = [...document.querySelectorAll('.message, .work-card, .task-card')].filter(el => {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < vh && r.right > 0 && r.left < innerWidth;
  }).length;
  return {
    visibleItems, overflowX: document.documentElement.scrollWidth > innerWidth,
    minContrast: minContrast === Infinity ? null : +minContrast.toFixed(2), failures,
  };
}

for (const [name, snapshot] of Object.entries(fixtures)) {
  for (const [width, height] of widths) {
    test(`${name} @${width}`, async () => {
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
      try {
        await page.addInitScript(() => { window.ziphapNative = { postMessage() {} }; });
        await page.goto(base);
        await page.waitForSelector('#root > *');
        await page.evaluate(([event, detail]) => window.dispatchEvent(new CustomEvent(event, { detail })), [SNAPSHOT_EVENT, snapshot]);
        await page.waitForTimeout(400);
        await page.screenshot({ path: path.join(out, `${name}-${width}.png`) });
        const m = await page.evaluate(measure);
        metrics.push({ fixture: name, width, visibleItems: m.visibleItems, overflowX: m.overflowX, minContrast: m.minContrast, contrastFailures: m.failures });
        assert.equal(m.overflowX, false, 'horizontal overflow');
        const unexpected = m.failures.filter(f => !KNOWN_CONTRAST_BASELINE.has(`${name}|${f.selector}`));
        assert.deepEqual(unexpected, [], 'contrast below threshold');
      } finally { await page.close(); }
    });
  }
}
