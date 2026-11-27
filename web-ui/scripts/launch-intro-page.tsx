// Static markup for the app-open intro (scripts/render-launch-intro.cjs):
//   vite-node scripts/launch-intro-page.tsx <out.html> <font-url>
// `idle` is the default 집순이 at rest; `wave` is the alert-state arm layer, which the render script copies onto it.
import { readFileSync, writeFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { Character } from '../src/character/Character'
import { DEFAULT_CHARACTER } from '../src/character/characterData'

const out = process.argv[2]
const font = process.argv[3]
if (!out || !font) throw new Error('usage: vite-node scripts/launch-intro-page.tsx <out.html> <font-url>')
const css = (file: string) => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
const markup = renderToStaticMarkup(<div id="stage">
  <div id="figure"><Character choice={DEFAULT_CHARACTER} situation="idle" size={300} decorative /></div>
  <div id="wave-source"><Character choice={DEFAULT_CHARACTER} state="alert" size={300} decorative /></div>
  <div id="wordmark">ZipHAP</div>
</div>)
writeFileSync(out, `<!doctype html><meta charset="utf-8"><style>
@font-face{font-family:'SUIT Variable';src:url('${font}') format('woff2-variations'),url('${font}') format('woff2');font-weight:100 900}
${css('tokens.css')}${css('character/character.css')}
html,body{margin:0;width:960px;height:540px;overflow:hidden;background:var(--color-bg,#F3F4F8)}
#stage{position:relative;width:960px;height:540px;background:#F3F4F8}
#figure{position:absolute;left:330px;top:70px;width:300px;height:300px;transform-origin:50% 92%}
#wave-source{position:absolute;left:-1000px;top:0;opacity:0}
#wordmark{position:absolute;left:0;right:0;top:392px;text-align:center;font:800 56px/1 'SUIT Variable','SUIT',system-ui,sans-serif;letter-spacing:-0.035em;color:#141318}
svg{display:block;overflow:visible}
</style>${markup}`)
