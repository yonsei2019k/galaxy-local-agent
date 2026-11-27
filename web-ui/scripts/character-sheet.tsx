// Renders 집순이 as static markup for browser scripts:
//   vite-node scripts/character-sheet.tsx <out.html> [cell]           portrait sheet (scripts/render-jipsooni-portraits.cjs)
//   vite-node scripts/character-sheet.tsx <out.html> --situations      one figure per situation with the app's motion CSS
// Portrait layout matches CharacterPreference.portraitCell(): column = variant * 6 + expr, row = color.
import { readFileSync, writeFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { Character } from '../src/character/Character'
import { COLORS, EXPR_OPTIONS, SITUATION_LOOK, VARIANTS, type CharacterSituation } from '../src/character/characterData'

const out = process.argv[2]
if (!out) throw new Error('usage: vite-node scripts/character-sheet.tsx <out.html> [cell | --situations]')
const situations = process.argv[3] === '--situations'
const cell = situations ? 200 : Number(process.argv[3] ?? 128)
const css = (file: string) => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')

// One React tree, so useId() keeps every gradient and filter id unique on the page.
const markup = situations
  ? renderToStaticMarkup(<div id="sheet" style={{ display: 'flex', flexWrap: 'wrap', gap: 16, padding: 16 }}>
    {(Object.keys(SITUATION_LOOK) as CharacterSituation[]).map(situation =>
      <figure key={situation} data-situation-cell={situation} style={{ margin: 0, padding: 12, borderRadius: 24, background: '#F4F3F6', textAlign: 'center' }}>
        <Character choice={{ variant: 'bear', color: 'sky', expr: 'basic' }} situation={situation} size={cell} decorative />
        <figcaption style={{ font: '700 15px var(--font-display)' }}>{situation}</figcaption>
      </figure>)}
  </div>)
  : renderToStaticMarkup(<div id="sheet" style={{
    display: 'grid', gridTemplateColumns: `repeat(${VARIANTS.length * EXPR_OPTIONS.length}, ${cell}px)`,
    gridAutoRows: `${cell}px`, width: 'max-content',
  }}>
    {COLORS.flatMap(color => VARIANTS.flatMap(variant => EXPR_OPTIONS.map(expr =>
      <Character key={`${color.id}-${variant.id}-${expr.id}`} choice={{ variant: variant.id, color: color.id, expr: expr.id }}
        size={cell} decorative />)))}
  </div>)

// Static markup cannot know the viewer's motion setting (the hook's server value is "reduced"), so the
// situations page applies what useVisibleMotion does on the client before anything is measured.
const motionScript = situations ? `<script>
for (const svg of document.querySelectorAll('svg.character'))
  svg.setAttribute('data-animated', String(!matchMedia('(prefers-reduced-motion: reduce)').matches));
</script>` : ''

writeFileSync(out, `<!doctype html><meta charset="utf-8"><style>
${situations ? css('tokens.css') + css('character/character.css') + css('motion.css') : ''}
html,body{margin:0;background:${situations ? '#FFFFFF' : 'transparent'}}svg{display:block;overflow:visible}
:root{--font-display:"SUIT","Apple SD Gothic Neo","Noto Sans KR",system-ui,sans-serif}
</style>${markup}${motionScript}`)
