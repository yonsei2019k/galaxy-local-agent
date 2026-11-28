// Static markup for the launcher icon foreground (scripts/render-launcher-icon.cjs):
//   vite-node scripts/launcher-icon-page.tsx <out.html>
// The default 집순이 (butter bear hood) with the smile face on a transparent 108x108 CSS-px canvas, the
// adaptive icon's foreground layer. The figure sits inside the 66px safe zone circle (centered at 54, 54).
import { writeFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { Character } from '../src/character/Character'
import { DEFAULT_CHARACTER } from '../src/character/characterData'

const out = process.argv[2]
if (!out) throw new Error('usage: vite-node scripts/launcher-icon-page.tsx <out.html>')
// Figure size in canvas px: the drawn hood spans about 78% x 76% of it, so 68px keeps the ears and hood
// corners inside the 66px safe zone circle.
const SIZE = 68
const markup = renderToStaticMarkup(<div id="figure">
  <Character choice={{ ...DEFAULT_CHARACTER, expr: 'smile' }} size={SIZE} decorative />
</div>)
writeFileSync(out, `<!doctype html><meta charset="utf-8"><style>
html,body{margin:0;width:108px;height:108px;overflow:hidden;background:transparent}
#figure{position:absolute;left:${(108 - SIZE) / 2}px;top:${(108 - SIZE) / 2 + 1}px;width:${SIZE}px;height:${SIZE}px}
svg{display:block;overflow:visible}
</style>${markup}`)
