// 시안 맞춤 비교 그림(2026-11-28). 지금 web-ui/dist와 이전 빌드(--before DIST)를 393px 폭에서 찍고,
// 시안(concept_phones.png)·지금 폰 화면(device-now.png)이나 이전 빌드와 나란히 붙인 compare-*.png를 만든다.
// 사용: node concept-compare.cjs --refs <시안 그림 폴더> --out <shots 폴더> [--before <이전 dist>]
// 합성 자료만 쓴다(chat-fixtures.cjs). 개인정보 없음.
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./chat-fixtures.cjs');

const arg = name => { const i = process.argv.indexOf(name); return i > 0 ? path.resolve(process.argv[i + 1]) : null; };
const refs = arg('--refs');
const out = arg('--out');
const before = arg('--before');
const after = path.resolve(__dirname, '../../web-ui/dist');
if (!refs || !out) { console.error('usage: node concept-compare.cjs --refs DIR --out DIR [--before DIST]'); process.exit(2); }
fs.mkdirSync(out, { recursive: true });

const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.woff2': 'font/woff2', '.ttf': 'font/ttf' };
function serve(dist) {
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.join(dist, rel);
    if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

// 시안의 음성 입력 아이콘까지 보이도록 마이크·음성 대화가 켜진 상태로 찍는다.
const withVoice = snapshot => ({ ...snapshot, voiceDraftEpoch: 1 });
const scenes = {
  empty: withVoice(fixtures.empty),
  running: withVoice(fixtures.conceptRunning),
  paused: withVoice(fixtures.devicePaused),
  choices: withVoice(fixtures.choices),
  approval: withVoice(fixtures.approval),
};

async function shoot(browser, base, name, snapshot, { drawer = false } = {}) {
  const page = await browser.newPage({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, reducedMotion: 'reduce' });
  await page.addInitScript(() => { window.ziphapNative = { postMessage() {} }; });
  await page.goto(base);
  await page.waitForSelector('#root > *');
  await page.waitForFunction(detail => {
    window.dispatchEvent(new CustomEvent('ziphap:snapshot', { detail }));
    return !document.querySelector('.loading') && document.querySelector('.composer-footer');
  }, snapshot, { polling: 50, timeout: 5000 });
  if (drawer) await page.getByRole('button', { name: '대화 및 앱 메뉴 열기' }).click();
  await page.evaluate(async () => {
    await document.fonts.ready;
    for (const animation of document.getAnimations()) animation.finish?.();
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  const buffer = await page.screenshot();
  const clip = async selector => {
    const box = await page.locator(selector).first().boundingBox();
    return box ? page.screenshot({ clip: { x: 0, y: box.y - 6, width: 393, height: box.height + 12 } }) : null;
  };
  const parts = { bar: await clip('.bottom-nav'), composer: await clip('.composer-footer') };
  await page.close();
  return { full: buffer, ...parts, name };
}

const data = buffer => `data:image/png;base64,${buffer.toString('base64')}`;
const file = name => data(fs.readFileSync(path.join(refs, name)));

// 패널: { label, src, crop?: [x, y, w, h] in source px, width }
async function compose(browser, name, title, panels) {
  const cells = panels.map(p => {
    const img = p.crop
      ? `<div style="width:${p.width}px;height:${Math.round(p.width * p.crop[3] / p.crop[2])}px;background:url(${p.src}) no-repeat;` +
        `background-size:${p.width * p.natural[0] / p.crop[2]}px auto;background-position:-${p.width * p.crop[0] / p.crop[2]}px -${p.width * p.crop[1] / p.crop[2]}px;border-radius:18px"></div>`
      : `<img src="${p.src}" style="width:${p.width}px;display:block;border-radius:18px">`;
    return `<figure>${img}<figcaption>${p.label}</figcaption></figure>`;
  }).join('');
  const page = await browser.newPage({ viewport: { width: 400, height: 400 }, deviceScaleFactor: 1 });
  await page.setContent(`<!doctype html><meta charset="utf-8"><style>
    body{margin:0;padding:20px;background:#E9E9EE;font:600 15px system-ui,sans-serif;color:#141318;width:max-content}
    h1{font-size:17px;margin:0 0 14px} .row{display:flex;gap:20px;align-items:flex-start}
    figure{margin:0;background:#fff;padding:10px;border-radius:24px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
    figcaption{margin-top:8px;text-align:center}</style><h1>${title}</h1><div class="row">${cells}</div>`);
  await page.evaluate(() => Promise.all([...document.images].map(i => i.decode().catch(() => {}))));
  await page.screenshot({ path: path.join(out, `compare-${name}.png`), fullPage: true });
  await page.close();
}

async function natural(browser, src) {
  const page = await browser.newPage();
  await page.setContent(`<img src="${src}">`);
  const size = await page.evaluate(() => document.images[0].decode().then(() => [document.images[0].naturalWidth, document.images[0].naturalHeight]));
  await page.close();
  return size;
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const afterServer = await serve(after);
  const beforeServer = before ? await serve(before) : null;
  const url = server => `http://127.0.0.1:${server.address().port}/`;
  const now = {}, old = {};
  for (const [name, snapshot] of Object.entries(scenes)) {
    now[name] = await shoot(browser, url(afterServer), name, snapshot);
    fs.writeFileSync(path.join(out, `after-${name}-393.png`), now[name].full);
    if (beforeServer) old[name] = await shoot(browser, url(beforeServer), name, snapshot);
  }
  now.drawer = await shoot(browser, url(afterServer), 'drawer', scenes.empty, { drawer: true });
  fs.writeFileSync(path.join(out, 'after-drawer-393.png'), now.drawer.full);
  if (beforeServer) old.drawer = await shoot(browser, url(beforeServer), 'drawer', scenes.empty, { drawer: true });

  const phones = file('concept_phones.png');
  const phonesSize = await natural(browser, phones);
  const device = file('device-now.png');
  const deviceSize = await natural(browser, device);
  const half = Math.floor(phonesSize[0] / 2);
  const W = 300;
  const shot = (label, buffer) => ({ label, src: data(buffer), width: W });
  const beforeShot = name => old[name] ? [shot('이전(v0.2.17)', old[name].full)] : [];
  await compose(browser, 'empty', '(a) 빈 대화: 시안 · 이번 화면', [
    { label: '시안', src: phones, natural: phonesSize, crop: [0, 0, half, phonesSize[1]], width: W },
    ...beforeShot('empty'), shot('이번 화면', now.empty.full)]);
  await compose(browser, 'running', '(b) 진행 중 작업: 시안 오른쪽 · 이번 화면', [
    { label: '시안', src: phones, natural: phonesSize, crop: [half, 0, phonesSize[0] - half, phonesSize[1]], width: W },
    ...beforeShot('running'), shot('이번 화면', now.running.full)]);
  await compose(browser, 'paused', '(c) 일시 정지와 진행 보고: 지금 폰 · 이번 화면', [
    { label: '지금 폰(v0.2.17)', src: device, natural: deviceSize, crop: [0, 0, deviceSize[0], deviceSize[1]], width: W },
    ...beforeShot('paused'), shot('이번 화면', now.paused.full)]);
  await compose(browser, 'choices', '(d) 선택지 칩', [...beforeShot('choices'), shot('이번 화면', now.choices.full)]);
  await compose(browser, 'approval', '(e) 승인 카드 자리(카드 내용은 휴대폰이 그린다)', [...beforeShot('approval'), shot('이번 화면', now.approval.full)]);
  await compose(browser, 'drawer', '메뉴(서랍) 아이콘: 이전 · 이번', [...(old.drawer ? [shot('이전(v0.2.17)', old.drawer.full)] : []), shot('이번 화면', now.drawer.full)]);
  const strip = (label, buffer) => ({ label, src: data(buffer), width: 393 });
  await compose(browser, 'bottom-bar', '하단 탭: 이전 · 이번', [
    ...(old.empty?.bar ? [strip('이전', old.empty.bar)] : []), strip('이번', now.empty.bar)]);
  await compose(browser, 'composer', '입력창(빈 대화 · 진행 중): 이전 · 이번', [
    ...(old.empty?.composer ? [strip('이전 빈 대화', old.empty.composer), strip('이전 진행 중', old.running.composer)] : []),
    strip('이번 빈 대화', now.empty.composer), strip('이번 진행 중', now.running.composer)]);
  await browser.close();
  afterServer.close(); beforeServer?.close();
  console.log(`wrote compare-*.png to ${out}`);
})().catch(error => { console.error(error); process.exit(1); });
