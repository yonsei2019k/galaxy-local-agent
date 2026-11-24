// 아이콘·그림 증명 시트: scripts/gen-icons.py --sheet 로 HTML을 만들고 Chromium으로 PNG를 찍는다.
// 사용: node icon-sheet.cjs  (결과: out/icon-sheet.html, out/icon-sheet.png, out/icon-sheet@2x-*.png)
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const out = path.join(__dirname, 'out');
const html = path.join(out, 'icon-sheet.html');
const python = process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3');

(async () => {
  fs.mkdirSync(out, { recursive: true });
  execFileSync(python, [path.join(__dirname, '..', 'gen-icons.py'), '--sheet', html], { stdio: 'inherit' });
  const browser = await chromium.launch({ headless: true, ...(process.env.CHAT_VISUAL_CHANNEL ? { channel: process.env.CHAT_VISUAL_CHANNEL } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
    await page.goto('file://' + html.replace(/\\/g, '/'));
    await page.screenshot({ path: path.join(out, 'icon-sheet.png'), fullPage: true });
    const zoom = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
    await zoom.goto('file://' + html.replace(/\\/g, '/'));
    const grid = await zoom.$('.grid');
    await grid.screenshot({ path: path.join(out, 'icon-sheet@2x-icons.png') });
    for (const id of ['zoom0', 'zoom1']) await (await zoom.$('#' + id)).screenshot({ path: path.join(out, `icon-sheet-${id}.png`) });
    const ills = await zoom.$('.ills');
    await ills.screenshot({ path: path.join(out, 'icon-sheet@2x-illustrations.png') });
    console.log('wrote', path.join(out, 'icon-sheet.png'));
  } finally {
    await browser.close();
  }
})().catch(err => { console.error(err); process.exit(1); });
