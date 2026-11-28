#!/usr/bin/env node
// Regenerates the launcher icon foreground from the web 집순이 drawing (default butter bear hood, smiling):
//   app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png  (108dp: 108..432px)
// Each density is rendered by Chromium at its own device scale, so the vector figure stays sharp (no bitmap scaling).
// The background (#F3F4F8) and the themed-icon monochrome silhouette are XML resources next to these.
// Usage (after `npm ci` in web-ui and scripts/browser-validation):
//   node scripts/render-launcher-icon.cjs [--shots <dir>]
// --shots also saves previews of the icon on circle, squircle and rounded-square masks at 48dp and 96dp.
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { chromium } = require('./browser-validation/node_modules/playwright')

const root = path.resolve(__dirname, '..')
const args = process.argv.slice(2)
const shotsIndex = args.indexOf('--shots')
const shotsDir = shotsIndex >= 0 ? path.resolve(args[shotsIndex + 1]) : null
const res = path.join(root, 'app/src/main/res')
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }
const BACKGROUND = '#F3F4F8' // @color/ziphap_launch_background

;(async () => {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'ziphap-launcher-'))
  const html = path.join(work, 'launcher.html')
  const webUi = path.join(root, 'web-ui')
  const viteNode = path.join(webUi, 'node_modules', 'vite-node', 'vite-node.mjs')
  execFileSync(process.execPath, [viteNode, 'scripts/launcher-icon-page.tsx', html], { cwd: webUi, stdio: 'inherit' })
  const browser = await chromium.launch({ headless: true })
  try {
    for (const [density, scale] of Object.entries(DENSITIES)) {
      const page = await browser.newPage({ viewport: { width: 108, height: 108 }, deviceScaleFactor: scale })
      await page.goto('file:///' + html.split(path.sep).join('/'))
      const out = path.join(res, `mipmap-${density}`, 'ic_launcher_foreground.png')
      fs.mkdirSync(path.dirname(out), { recursive: true })
      await page.screenshot({ path: out, omitBackground: true })
      await page.close()
      console.log(`wrote ${path.relative(root, out)} (${108 * scale}px)`)
    }
    if (shotsDir) {
      fs.mkdirSync(shotsDir, { recursive: true })
      const fg = 'data:image/png;base64,' + fs.readFileSync(path.join(res, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png')).toString('base64')
      const mono = fs.readFileSync(path.join(res, 'drawable', 'ic_launcher_monochrome.xml'), 'utf8')
      // The launcher shows the middle 72dp of the 108dp layers; the mask decides the outline.
      const squircle = 'M50 0 C88 0 100 12 100 50 C100 88 88 100 50 100 C12 100 0 88 0 50 C0 12 12 0 50 0 Z'
      const masks = { circle: 'border-radius:50%', squircle: `clip-path:path('${squircle}')`, rounded: 'border-radius:22%' }
      const tile = (size, mask) => {
        const style = mask === 'squircle'
          ? `clip-path:path('${squircle.replace(/[\d.]+/g, n => String(+n * size / 100))}')` : masks[mask]
        return `<div class="icon" style="width:${size}px;height:${size}px;${style}">` +
          `<img src="${fg}" style="width:${size * 1.5}px;height:${size * 1.5}px;margin:-${size / 4}px"></div>`
      }
      const cells = [48, 96].map(dp => Object.keys(masks).map(mask =>
        `<figure>${tile(dp * 2, mask)}<figcaption>${mask} · ${dp}dp</figcaption></figure>`).join('')).join('')
      const paths = [...mono.matchAll(/android:pathData="([^"]+)"/g)].map(m => m[1])
      // Same group transform as ic_launcher_monochrome.xml; the launcher shows the middle 72 of the 108 viewport.
      const monoSvg = `<svg viewBox="18 18 72 72" width="192" height="192" style="border-radius:50%;background:#2C3A4A">` +
        `<g transform="translate(24 21.6) scale(0.3)" fill="#CFE3FF">${paths.map(d => `<path d="${d}" fill-rule="evenodd"/>`).join('')}</g></svg>`
      const sheet = path.join(work, 'preview.html')
      fs.writeFileSync(sheet, `<!doctype html><meta charset="utf-8"><style>
        body{margin:0;padding:24px;background:#DCDDE3;font:600 14px system-ui,sans-serif;width:max-content}
        .row{display:flex;gap:24px;align-items:flex-end;flex-wrap:wrap;max-width:1100px}
        figure{margin:0;text-align:center} figcaption{margin-top:8px}
        .icon{overflow:hidden;background:${BACKGROUND};box-shadow:0 2px 8px rgba(0,0,0,.18)}
        .icon img{display:block}
        .wall{margin-top:20px;padding:20px;border-radius:20px;background:linear-gradient(135deg,#5B7FA8,#9B7BB8);display:flex;gap:24px;align-items:center;color:#fff}
      </style><div class="row">${cells}</div>
      <div class="wall">${tile(96, 'circle')}${tile(96, 'squircle')}<figure>${monoSvg}<figcaption>themed (monochrome)</figcaption></figure></div>`)
      const page = await browser.newPage({ viewport: { width: 1200, height: 800 }, deviceScaleFactor: 1 })
      await page.goto('file:///' + sheet.split(path.sep).join('/'))
      await page.screenshot({ path: path.join(shotsDir, 'launcher-icon-preview.png'), fullPage: true })
      await page.close()
      console.log(`wrote ${path.join(shotsDir, 'launcher-icon-preview.png')}`)
    }
  } finally {
    await browser.close()
    fs.rmSync(work, { recursive: true, force: true })
  }
})().catch(error => { console.error(error); process.exit(1) })
