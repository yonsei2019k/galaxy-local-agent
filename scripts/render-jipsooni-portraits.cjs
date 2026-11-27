#!/usr/bin/env node
// Regenerates app/src/main/res/raw/jipsooni_portraits.png from the web 집순이 drawing.
// Usage (after `npm ci` in web-ui and scripts/browser-validation):
//   node scripts/render-jipsooni-portraits.cjs [--out <png>]
// The sheet is 18 columns (variant x expression) by 8 rows (color) of 128px cells on a transparent
// background, the layout CharacterPreference.portraitCell() and SettingsAssistantPanel read.
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { chromium } = require('./browser-validation/node_modules/playwright')

const root = path.resolve(__dirname, '..')
const args = process.argv.slice(2)
const outIndex = args.indexOf('--out')
const out = path.resolve(outIndex >= 0 ? args[outIndex + 1] : path.join(root, 'app/src/main/res/raw/jipsooni_portraits.png'))
const CELL = 128
const COLUMNS = 18
const ROWS = 8

async function main() {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'jipsooni-'))
  const html = path.join(work, 'sheet.html')
  const webUi = path.join(root, 'web-ui')
  const viteNode = path.join(webUi, 'node_modules', 'vite-node', 'vite-node.mjs')
  execFileSync(process.execPath, [viteNode, 'scripts/character-sheet.tsx', html, String(CELL)], { cwd: webUi, stdio: 'inherit' })
  const browser = await chromium.launch()
  try {
    const page = await browser.newPage({ viewport: { width: COLUMNS * CELL, height: ROWS * CELL }, deviceScaleFactor: 1 })
    await page.goto('file://' + html.replace(/\\/g, '/'))
    const sheet = page.locator('#sheet')
    const box = await sheet.boundingBox()
    if (!box || Math.round(box.width) !== COLUMNS * CELL || Math.round(box.height) !== ROWS * CELL) {
      throw new Error(`unexpected sheet size ${JSON.stringify(box)}`)
    }
    if (await page.locator('svg.character').count() !== COLUMNS * ROWS) throw new Error('missing portrait cells')
    await sheet.screenshot({ path: out, omitBackground: true })
  } finally {
    await browser.close()
    fs.rmSync(work, { recursive: true, force: true })
  }
  console.log(`${path.relative(root, out)} ${fs.statSync(out).size} bytes`)
}

main().catch(error => { console.error(error); process.exit(1) })
