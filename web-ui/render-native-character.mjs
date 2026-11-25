// Regenerate the static Android settings portrait after changing the React character artwork.
// Run from web-ui: node render-native-character.mjs /path/to/chrome
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, copyFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

const browser = process.argv[2]
if (!browser) throw new Error('Pass the path to a Chromium browser executable')
const root = fileURLToPath(new URL('.', import.meta.url))
const output = fileURLToPath(new URL('../app/src/main/res/raw/jipsooni_portraits.png', import.meta.url))
const server = await createServer({ root, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
const temp = mkdtempSync(join(tmpdir(), 'ziphap-portraits-'))
try {
  const { Character } = await server.ssrLoadModule('/src/character/Character.tsx')
  const { COLORS, VARIANTS, EXPR_OPTIONS } = await server.ssrLoadModule('/src/character/characterData.ts')
  const size = 128
  const width = size * VARIANTS.length * EXPR_OPTIONS.length
  const height = size * COLORS.length
  const cells = []
  for (const { id: color } of COLORS) for (const { id: variant } of VARIANTS) for (const { id: expr } of EXPR_OPTIONS) {
    cells.push(renderToStaticMarkup(createElement(Character, { size, choice: { color, variant, expr } })))
  }
  const page = join(temp, 'portraits.html')
  writeFileSync(page, '<style>*{margin:0}body{display:grid;grid-template-columns:repeat(' +
    VARIANTS.length * EXPR_OPTIONS.length + ',128px)}svg{display:block}</style>' + cells.join(''))
  const png = join(temp, 'portraits.png')
  execFileSync(browser, ['--headless', '--disable-gpu', '--no-first-run', '--disable-extensions',
    '--disable-background-networking', '--hide-scrollbars', '--default-background-color=00000000',
    '--force-device-scale-factor=1', '--user-data-dir=' + join(temp, 'profile'),
    '--window-size=' + width + ',' + height, '--screenshot=' + png, pathToFileURL(page).href], { windowsHide: true })
  const bytes = readFileSync(png)
  if (bytes.readUInt32BE(16) !== width || bytes.readUInt32BE(20) !== height) throw new Error('Unexpected image size')
  copyFileSync(png, output)
} finally {
  await server.close()
  rmSync(temp, { recursive: true, force: true, maxRetries: 3 })
}
