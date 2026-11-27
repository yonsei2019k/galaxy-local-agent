// 집순이 상황별 동작 확인(2026-11-27). 웹의 실제 motion.css로 상황마다 캐릭터를 그려서
// - 움직임 허용: 상황마다 동작이 있고, 한 번만 하는 상황(인사·확인·완료·막힘)은 반복하지 않는다.
// - 움직임 줄이기(CHAT_VISUAL_REDUCED_MOTION=1): 캐릭터 동작이 하나도 없다(정지 자세).
// - 화면 밖(data-paused)에서는 모든 동작이 멈춘다.
// 상황별 스크린샷은 out/situations/(움직임 줄이기는 out/reduced-motion/situations/)에 남긴다.
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test, before, after } = require('node:test');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '../..');
const reducedMotion = process.env.CHAT_VISUAL_REDUCED_MOTION === '1' ? 'reduce' : 'no-preference';
const shotDir = path.join(__dirname, 'out', reducedMotion === 'reduce' ? 'reduced-motion' : '', 'situations');
const ONCE = new Set(['greeting', 'confirm', 'done', 'blocked']);
const SITUATIONS = ['idle', 'greeting', 'listening', 'thinking', 'working', 'waiting', 'confirm', 'done', 'blocked'];

let browser, page, work, initial;

before(async () => {
  fs.mkdirSync(shotDir, { recursive: true });
  work = fs.mkdtempSync(path.join(os.tmpdir(), 'jipsooni-situations-'));
  const html = path.join(work, 'situations.html');
  const webUi = path.join(root, 'web-ui');
  execFileSync(process.execPath, [path.join(webUi, 'node_modules', 'vite-node', 'vite-node.mjs'), 'scripts/character-sheet.tsx', html, '--situations'],
    { cwd: webUi, stdio: 'inherit' });
  browser = await chromium.launch({ headless: true });
  page = await browser.newPage({ viewport: { width: 760, height: 900 }, deviceScaleFactor: 1, reducedMotion });
  await page.goto('file://' + html.replace(/\\/g, '/'));
  // One-shot motions finish within a second, so record every animation right after the page opens.
  initial = await page.evaluate(() => Object.fromEntries([...document.querySelectorAll('[data-situation-cell]')].map(cell => [
    cell.dataset.situationCell,
    cell.querySelector('svg.character').getAnimations({ subtree: true }).map(animation => ({
      name: animation.animationName, iterations: animation.effect.getComputedTiming().iterations,
    })),
  ])));
});

after(async () => {
  await browser?.close();
  if (work) fs.rmSync(work, { recursive: true, force: true });
});

test('every situation is drawn with its own marker', async () => {
  const found = await page.evaluate(() => [...document.querySelectorAll('svg.character')].map(svg => svg.getAttribute('data-situation')));
  assert.deepEqual(found, SITUATIONS);
});

for (const situation of SITUATIONS) {
  test(`situation ${situation} motion`, async () => {
    const animations = initial[situation];
    if (reducedMotion === 'reduce') {
      assert.deepEqual(animations, [], 'reduced motion shows one still pose');
    } else {
      const own = animations.filter(animation => animation.name.startsWith('sit-'));
      assert.ok(own.length > 0 || situation === 'waiting', `situation motion missing: ${JSON.stringify(animations)}`);
      if (situation === 'waiting') assert.ok(animations.some(animation => animation.name === 'character-breathe'), 'slow breathing');
      if (ONCE.has(situation)) {
        for (const animation of own) assert.notEqual(animation.iterations, Infinity, `${animation.name} must play once`);
      }
    }
    // Capture the motion part-way so the pose is visible, then the end state of one-shot motions.
    await page.evaluate(name => {
      const svg = document.querySelector(`[data-situation-cell="${name}"] svg.character`);
      for (const animation of svg.getAnimations({ subtree: true })) {
        const timing = animation.effect.getComputedTiming();
        animation.pause();
        animation.currentTime = (Number(timing.duration) || 0) * 0.45;
      }
    }, situation);
    await page.locator(`[data-situation-cell="${situation}"]`).screenshot({ path: path.join(shotDir, `${situation}.png`) });
  });
}

test('offscreen characters pause every situation motion', async () => {
  if (reducedMotion === 'reduce') return;
  const states = await page.evaluate(() => {
    for (const svg of document.querySelectorAll('svg.character')) {
      svg.setAttribute('data-paused', 'true');
      for (const animation of svg.getAnimations({ subtree: true })) animation.play();
    }
    return [...document.querySelectorAll('svg.character')].flatMap(svg =>
      [...svg.querySelectorAll('*'), svg].map(element => getComputedStyle(element))
        .filter(style => style.animationName !== 'none').map(style => style.animationPlayState));
  });
  assert.ok(states.length > 0);
  assert.ok(states.every(state => state.split(',').every(part => part.trim() === 'paused')), JSON.stringify([...new Set(states)]));
});

test('situation sheet screenshot', async () => {
  await page.screenshot({ path: path.join(shotDir, 'all.png'), fullPage: true });
});
