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
