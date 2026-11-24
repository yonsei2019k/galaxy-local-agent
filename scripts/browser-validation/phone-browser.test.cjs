const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { chromium, devices } = require('playwright');

const source = readFileSync(path.join(__dirname, '../../public/app-web-agent.js'), 'utf8');

test('phone browser asset checks actionability and refreshes stale references in Chromium', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage(devices['Pixel 7']);
    await page.setContent(`
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <button id="next"><span>Continue fixture</span></button>
      <style>
        #screen { position: fixed; inset: 0; z-index: 5; }
        #next { margin-top: 110vh; }
      </style>
      <script>
        window.steps = 0;
        document.querySelector('#next').onclick = () => window.steps++;
      </script>`);
    const run = options => page.evaluate(`(${source})(${JSON.stringify({
      key: 'ziphap-fixture', approvedSnapshot: null, ...options,
    })})`);
    const observe = snapshotId => run({ action: 'observe', snapshotId });
    const click = (snapshotId, ref) => run({ action: 'click', snapshotId, ref });

    const first = await observe('first');
    const ref = first.elements.find(element => element.label === 'Continue fixture').ref;
    await page.evaluate(() => {
      const overlay = document.createElement('div');
      overlay.id = 'screen';
      document.body.append(overlay);
    });
    assert.deepEqual(await click('first', ref), { error: 'ELEMENT_OBSCURED' });
    assert.equal(await page.evaluate(() => window.steps), 0);
    assert.deepEqual(await click('first', ref), { error: 'SNAPSHOT_STALE' });

    await page.locator('#screen').evaluate(element => { element.style.pointerEvents = 'none'; });
    await observe('second');
    assert.deepEqual(await click('second', ref), { performed: true });
    assert.equal(await page.evaluate(() => window.steps), 1);

    await observe('third');
    await page.locator('#next').evaluate(element => element.replaceWith(element.cloneNode(true)));
    assert.deepEqual(await click('third', ref), { error: 'SNAPSHOT_STALE' });
    assert.equal(await page.evaluate(() => window.steps), 1);

    await observe('fourth');
    await page.locator('#next').evaluate(element => element.setAttribute('inert', ''));
    assert.deepEqual(await click('fourth', ref), { error: 'ELEMENT_OBSCURED' });
    assert.equal(await page.evaluate(() => window.steps), 1);

    const touchPage = await browser.newPage(devices['Pixel 7']);
    await touchPage.setContent(`<meta name="viewport" content="width=device-width, initial-scale=1">
      <button onclick="window.trusted = event.isTrusted">Enter classroom</button>`);
    const touch = options => touchPage.evaluate(`(${source})(${JSON.stringify({
      key:'ziphap-touch-fixture', approvedSnapshot:null, ...options,
    })})`);
    const current = await touch({ action:'observe', snapshotId:'trusted' });
    const touchRef = current.elements.find(element => element.label === 'Enter classroom').ref;
    const { trustedTap } = await touch({ action:'prepareTrustedTap', snapshotId:'trusted', ref:touchRef });
    assert.ok(trustedTap.x > 0 && trustedTap.x < trustedTap.viewportWidth);
    assert.equal(await touchPage.evaluate(() => window.trusted), undefined);
    await touchPage.touchscreen.tap(trustedTap.x, trustedTap.y);
    assert.equal(await touchPage.evaluate(() => window.trusted), true);
    assert.deepEqual(await touch({ action:'prepareTrustedTap', snapshotId:'trusted', ref:touchRef }), { error:'SNAPSHOT_STALE' });
  } finally {
    await browser.close();
  }
});

test('copies an already issued, observed blob without opening or clicking it', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage(devices['Pixel 7']);
    await page.route('https://fixture.example.test/issued', route => route.fulfill({
      contentType: 'text/html',
      body: `<a download="fixture.pdf">PDF fixture</a><script>
        document.querySelector('a').href = URL.createObjectURL(new Blob(['%PDF-1.7 fixture'], {type:'application/pdf'}));
      </script>`,
    }));
    await page.goto('https://fixture.example.test/issued');
    const run = options => page.evaluate(`(${source})(${JSON.stringify({
      key: 'ziphap-blob-fixture', snapshotId: 'blob-snapshot', approvedSnapshot: null, ...options,
    })})`);
    const observed = await run({action:'observe'});
    const element = observed.elements.find(item => item.label === 'PDF fixture');
    assert.ok(element.ruleHref.startsWith('blob:https://fixture.example.test/'));
    const destination = {ref:element.ref, href:element.ruleHref, transferId:'capture'};
    assert.deepEqual(await run({...destination, action:'startBlobPdf'}), {started:true});
    let part;
    for (let attempt=0; attempt<20; attempt++) {
      part = await run({...destination, action:'readBlobPdf', offset:0});
      if (!part.pending) break;
      await new Promise(resolve => setTimeout(resolve,25));
    }
    assert.equal(Buffer.from(part.chunk,'base64').toString(),'%PDF-1.7 fixture');
    await page.locator('a').evaluate(link => link.href='https://fixture.example.test/new.pdf');
    assert.deepEqual(await run({...destination, action:'readBlobPdf', offset:0}), {error:'SNAPSHOT_STALE'});
    assert.equal(page.url(),'https://fixture.example.test/issued');
  } finally {
    await browser.close();
  }
});

test('observes a real muted clip ending before opening the next lesson', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage(devices['Pixel 7']);
    await page.setContent(`
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <h1>Lesson 1</h1><video muted width="160" height="90" playsinline></video>
      <button id="play"><img alt="Start lesson" width="40" height="20"></button>
      <button id="next" disabled>Next lesson</button>
      <script>
        const video = document.querySelector('video');
        const next = document.querySelector('#next');
        document.querySelector('#play').onclick = () => video.play();
        video.onended = () => { next.disabled = false; };
        next.onclick = () => { document.querySelector('h1').textContent = 'Lesson 2'; };
      </script>`);
    await page.evaluate(async () => {
      const canvas = Object.assign(document.createElement('canvas'), {width: 64, height: 36});
      const ctx = canvas.getContext('2d');
      const stream = canvas.captureStream(12);
      const recorder = new MediaRecorder(stream, {mimeType: 'video/webm'});
      const chunks = [];
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
      const stopped = new Promise(resolve => { recorder.onstop = resolve; });
      recorder.start();
      for (let index = 0; index < 16; index++) {
        ctx.fillStyle = index % 2 ? '#03f' : '#04f';
        ctx.fillRect(0, 0, 64, 36);
        await new Promise(resolve => setTimeout(resolve, 84));
      }
      recorder.stop();
      await stopped;
      stream.getTracks().forEach(track => track.stop());
      const video = document.querySelector('video');
      const ready = new Promise((resolve, reject) => {
        video.addEventListener('loadeddata', resolve, {once: true});
        video.addEventListener('error', reject, {once: true});
      });
      video.src = URL.createObjectURL(new Blob(chunks, {type: 'video/webm'}));
      await ready;
    });
    const run = options => page.evaluate(`(${source})(${JSON.stringify({
      key: 'ziphap-lesson-fixture', approvedSnapshot: null, ...options,
    })})`);
    const observe = snapshotId => run({action: 'observe', snapshotId, observeMedia: true});
    const start = await observe('a'.repeat(32));
    assert.equal(start.media.paused, true);
    assert.equal(start.media.ended, false);
    assert.equal(await page.locator('#next').isEnabled(), false);
    const play = start.elements.find(element => element.label === 'Start lesson');
    assert.deepEqual(await run({action:'click', snapshotId:'a'.repeat(32), ref:play.ref}), {performed:true});
    await page.waitForFunction(() => document.querySelector('video').currentTime > 0.2);
    const playing = await run({action:'observeBoundMedia', snapshotId:'b'.repeat(32)});
    assert.equal(playing.media.ended, false);
    assert.ok(playing.media.positionMs > start.media.positionMs);
    await page.waitForFunction(() => document.querySelector('video').ended);
    const ended = await observe('c'.repeat(32));
    assert.equal(ended.media.playerId, start.media.playerId);
    assert.equal(ended.media.epoch, start.media.epoch);
    assert.equal(ended.media.ended, true);
    const next = ended.elements.find(element => element.label === 'Next lesson');
    assert.deepEqual(await run({action:'click', snapshotId:'c'.repeat(32), ref:next.ref}), {performed:true});
    assert.equal(await page.locator('h1').textContent(), 'Lesson 2');
  } finally {
    await browser.close();
  }
});
