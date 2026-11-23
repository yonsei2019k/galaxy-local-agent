const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { chromium } = require('playwright');

const source = readFileSync(path.join(__dirname, '../../public/app-web-login.js'), 'utf8');

test('secure login card sends credentials only to its current same-origin POST form', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const posts = [];
    const origin = 'https://fixture.example';
    await page.route('**/*', async route => {
      if (route.request().method() === 'POST') {
        posts.push([route.request().url(), route.request().postData()]);
        await route.fulfill({ contentType: 'text/html', body: '<h1>Signed in</h1>' });
      } else await route.fulfill({ contentType: 'text/html', body: `
        <form method="post" action="/session">
          <input name="user" required><input name="password" type="password" required>
          <button>Sign in</button>
        </form>` });
    });
    const run = (action, extra = {}) => page.evaluate(`(${source})(${JSON.stringify({
      action, origin, key: 'ziphap-login-fixture', id: 1, ...extra,
    })})`);
    const fields = { account: 'synthetic-user', password: 'synthetic-password' };
    const form = page.locator('form');
    await page.goto(origin);
    assert.equal(await run('capture'), true);
    assert.equal(await run('submit', { ...fields, id: 2 }), false);
    await form.evaluate(node => { node.action = 'https://other.example/session'; });
    assert.equal(await run('submit', fields), false);
    assert.equal(posts.length, 0);
    assert.equal(await page.locator('input[type=password]').inputValue(), '');
    await form.evaluate(node => { node.action = '/session'; node.method = 'get'; });
    assert.equal(await run('capture'), false);
    await form.evaluate(node => { node.method = 'post'; });
    await page.locator('button').evaluate(node => { node.setAttribute('formaction', 'https://other.example/'); });
    assert.equal(await run('capture'), false);
    await page.locator('button').evaluate(node => { node.removeAttribute('formaction'); });
    assert.equal(await run('capture'), true);
    const reply = page.waitForResponse(response => response.url() === origin + '/session');
    assert.equal(await run('submit', fields), true);
    await reply;
    assert.deepEqual(posts, [[origin + '/session', 'user=synthetic-user&password=synthetic-password']]);
    await page.getByRole('heading', { name: 'Signed in' }).waitFor();
    assert.equal(await run('submit', fields), false);
  } finally {
    await browser.close();
  }
});
