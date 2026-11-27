#!/usr/bin/env node
// Regenerates the app-open intro art from the web 집순이 drawing:
//   app/src/main/res/drawable-nodpi/ziphap_launch_intro.webp  (960x540 poster)
//   app/src/main/res/raw/ziphap_launch_intro.mp4              (~2.2s, 30fps, last frame == poster)
// Usage (after `npm ci` in web-ui and scripts/browser-validation; ffmpeg on PATH):
//   node scripts/render-launch-intro.cjs [--shots <dir>]
// --shots also saves the poster PNG and a few frames for review (kept outside Git).
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { chromium } = require('./browser-validation/node_modules/playwright')

const root = path.resolve(__dirname, '..')
const args = process.argv.slice(2)
const shotsIndex = args.indexOf('--shots')
const shotsDir = shotsIndex >= 0 ? path.resolve(args[shotsIndex + 1]) : null
const posterOut = path.join(root, 'app/src/main/res/drawable-nodpi/ziphap_launch_intro.webp')
const videoOut = path.join(root, 'app/src/main/res/raw/ziphap_launch_intro.mp4')
const FPS = 30
const FRAMES = 66 // 2.2s
const W = 960
const H = 540

const clamp = (v) => Math.min(1, Math.max(0, v))
const easeOut = (t) => 1 - Math.pow(1 - t, 3)
const seg = (t, a, b) => clamp((t - a) / (b - a))

// Pose for time t (seconds). The last frames are the resting composition used by the poster.
function pose(t) {
  const popT = seg(t, 0, 0.5)
  // Pop in: grows past 1, settles with a small bounce.
  const scale = popT >= 1 ? 1 : 0.55 + 0.45 * easeOut(popT) + 0.09 * Math.sin(Math.PI * popT) * (1 - popT)
  const bounce = popT >= 1 ? 0 : -26 * Math.sin(Math.PI * popT) * (1 - popT)
  const opacity = clamp(popT * 3)
  const wordmark = easeOut(seg(t, 0.35, 0.7))
  const armIn = easeOut(seg(t, 0.55, 0.75)) * (1 - easeOut(seg(t, 1.55, 1.78)))
  const swing = t > 0.7 && t < 1.6 ? Math.sin(((t - 0.7) / 0.9) * Math.PI * 4) * 13 * Math.min(1, armIn * 2) : 0
  return { scale, bounce, opacity, wordmark, armIn, swing }
}

async function main() {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'launch-intro-'))
  const html = path.join(work, 'intro.html')
  const frames = path.join(work, 'frames')
  fs.mkdirSync(frames)
  const webUi = path.join(root, 'web-ui')
  const font = 'file:///' + path.join(webUi, 'public/fonts/SUIT-Variable.woff2').split(path.sep).join('/')
  const viteNode = path.join(webUi, 'node_modules', 'vite-node', 'vite-node.mjs')
  execFileSync(process.execPath, [viteNode, 'scripts/launch-intro-page.tsx', html, font], { cwd: webUi, stdio: 'inherit' })

  const browser = await chromium.launch()
  try {
    const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 })
    await page.goto('file:///' + html.split(path.sep).join('/'))
    await page.evaluate(() => document.fonts.ready)
    const fontOk = await page.evaluate(() => document.fonts.check("800 56px 'SUIT Variable'"))
    if (!fontOk) throw new Error('SUIT font did not load')
    // Move the alert-state arm onto the resting figure; everything else of the alert drawing stays hidden.
    await page.evaluate(() => {
      const figure = document.querySelector('#figure svg')
      const arm = document.querySelector('#wave-source [data-layer="wave"]')
      if (!figure || !arm) throw new Error('missing character layers')
      const holder = document.createElementNS('http://www.w3.org/2000/svg', 'g')
      holder.id = 'arm'
      holder.setAttribute('opacity', '0')
      holder.appendChild(arm.cloneNode(true))
      figure.appendChild(holder)
    })
    const render = (t) => page.evaluate((p) => {
      const figure = document.getElementById('figure')
      figure.style.opacity = String(p.opacity)
      figure.style.transform = `translateY(${p.bounce}px) scale(${p.scale})`
      document.getElementById('wordmark').style.opacity = String(p.wordmark)
      const arm = document.getElementById('arm')
      arm.setAttribute('opacity', String(p.armIn))
      arm.setAttribute('transform', `rotate(${p.swing} 150 160)`)
    }, pose(t))

    for (let i = 0; i < FRAMES; i++) {
      await render(i / FPS)
      await page.screenshot({ path: path.join(frames, `f${String(i).padStart(3, '0')}.png`) })
    }
    // Poster: the resting composition (also the video's last frame).
    await render(10)
    const posterPng = path.join(work, 'poster.png')
    await page.screenshot({ path: posterPng })
    const last = fs.readFileSync(path.join(frames, `f${String(FRAMES - 1).padStart(3, '0')}.png`))
    if (!last.equals(fs.readFileSync(posterPng))) throw new Error('last frame differs from the poster')

    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', posterPng, '-c:v', 'libwebp', '-quality', '92', posterOut], { stdio: 'inherit' })
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', path.join(frames, 'f%03d.png'),
      '-c:v', 'libx264', '-profile:v', 'main', '-pix_fmt', 'yuv420p', '-crf', '24', '-preset', 'slow',
      '-an', '-movflags', '+faststart', videoOut], { stdio: 'inherit' })
    if (shotsDir) {
      fs.mkdirSync(shotsDir, { recursive: true })
      fs.copyFileSync(posterPng, path.join(shotsDir, 'poster.png'))
      for (const i of [0, 12, 30, 45, FRAMES - 1]) fs.copyFileSync(path.join(frames, `f${String(i).padStart(3, '0')}.png`), path.join(shotsDir, `frame-${String(i).padStart(2, '0')}.png`))
    }
  } finally {
    await browser.close()
    fs.rmSync(work, { recursive: true, force: true })
  }
  for (const file of [posterOut, videoOut]) console.log(`${path.relative(root, file)} ${fs.statSync(file).size} bytes`)
}

main().catch(error => { console.error(error); process.exit(1) })
