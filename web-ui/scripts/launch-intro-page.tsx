// Static markup for the app-open intro (scripts/render-launch-intro.cjs):
//   vite-node scripts/launch-intro-page.tsx <out.html> <font-url>
// `rest` is the default 집순이 at rest; `wink` is the same figure with the greeting situation's wink face.
import { readFileSync, writeFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { Character } from '../src/character/Character'
import { DEFAULT_CHARACTER } from '../src/character/characterData'

const out = process.argv[2]
const font = process.argv[3]
if (!out || !font) throw new Error('usage: vite-node scripts/launch-intro-page.tsx <out.html> <font-url>')
const css = (file: string) => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const SIZE = 405
const markup = renderToStaticMarkup(<div id="stage">
  <div id="figure">
    <div id="rest"><Character choice={DEFAULT_CHARACTER} situation="idle" size={SIZE} decorative /></div>
    <div id="wink" style={{ display: 'none' }}><Character choice={DEFAULT_CHARACTER} situation="greeting" size={SIZE} decorative /></div>
  </div>
  <div id="wordmark">ZipHAP</div>
</div>)
writeFileSync(out, `<!doctype html><meta charset="utf-8"><style>
@font-face{font-family:'SUIT Variable';src:url('${font}') format('woff2-variations'),url('${font}') format('woff2');font-weight:100 900}
${css('tokens.css')}${css('character/character.css')}
html,body{margin:0;width:960px;height:540px;overflow:hidden;background:var(--color-bg,#F3F4F8)}
#stage{position:relative;width:960px;height:540px;background:#F3F4F8}
#figure{position:absolute;left:277px;top:17px;width:${SIZE}px;height:${SIZE}px;transform-origin:50% 93%}
#wordmark{position:absolute;left:0;right:0;top:424px;text-align:center;font:800 54px/1 'SUIT Variable','SUIT',system-ui,sans-serif;letter-spacing:-0.035em;color:#141318}
svg{display:block;overflow:visible}
</style>${markup}`)
