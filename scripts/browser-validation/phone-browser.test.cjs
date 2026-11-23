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
