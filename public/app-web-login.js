function (request) {
  "use strict";
  const doc = document;

  function visible(input) {
    const style = getComputedStyle(input);
    return input.getClientRects().length > 0 && style.visibility === 'visible' &&
      !input.matches(':disabled') && !input.readOnly && !input.closest('[inert]');
  }

  function candidate() {
    if (location.protocol !== 'https:' || location.origin !== request.origin) return null;
    const passwords = [...doc.querySelectorAll('input[type=password]')].filter(visible);
    if (passwords.length !== 1) return null;
    const password = passwords[0];
    const form = password.form;
    if (!form || !password.name || form.method !== 'post' || form.target && form.target !== '_self') return null;
    const action = new URL(form.action);
    if (action.origin !== request.origin || action.username || action.password) return null;

    const controls = [...form.elements];
    if (controls.some(field => /(?:^|\s)(?:cc-[a-z-]+|one-time-code)(?:\s|$)/i.test(field.autocomplete || '') ||
        /card[\s_.-]*(?:number|no|num|pin|cvv|cvc)|카드[\s_-]*(?:번호|비밀번호)/i.test(
          [field.name, field.id, field.placeholder].join(' ')))) return null;
    const identifiers = controls.filter(node => node instanceof HTMLInputElement &&
      ['text', 'email', 'tel'].includes(node.type) && node.name && visible(node));
    const submits = controls.filter(node => node.type === 'submit' && visible(node));
    if (identifiers.length !== 1 || submits.length !== 1) return null;
    const [submit] = submits;
    if (['formaction', 'formmethod', 'formtarget'].some(name => submit.hasAttribute(name))) return null;
    return {form, account: identifiers[0], password, submit, action: action.href, page: location.href};
  }

  if (request.action === 'authenticated') {
    if (location.origin !== request.origin || doc.readyState !== 'complete') return false;
    const fields = [...doc.querySelectorAll('input')].filter(visible);
    if (fields.some(field => field.type === 'password' ||
        /password|one-time-code|otp|인증\s*(?:번호|코드)|verification\s*code/i.test(
          [field.name, field.id, field.autocomplete, field.placeholder, field.getAttribute('aria-label'),
            ...Array.from(field.labels || [], label => label.innerText)].join(' ')))) return false;
    return [...doc.querySelectorAll('a[href],button,[role=button]')].some(node =>
      visible(node) && /^(?:로그아웃|sign out|log out)$/i.test(
        (node.getAttribute('aria-label') || node.innerText || '').trim()));
  }

  if (request.action === 'capture') {
    const bound = candidate();
    if (!bound) return false;
    Object.defineProperty(doc, request.key, {configurable: true, value: {id: request.id, bound}});
    return true;
  }
  if (request.action !== 'submit') return false;
  const saved = doc[request.key];
  if (!saved || saved.id !== request.id) return false;
  delete doc[request.key];
  const fresh = candidate();
  if (!fresh || Object.keys(saved.bound).some(key => fresh[key] !== saved.bound[key])) return false;
  const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  const oldAccount = fresh.account.value;
  setValue.call(fresh.account, request.account);
  setValue.call(fresh.password, request.password);
  try {
    if (!fresh.form.reportValidity()) return false;
    HTMLFormElement.prototype.requestSubmit.call(fresh.form, fresh.submit);
    return true;
  } finally {
    // Native form submission has already taken its data snapshot by the time click() returns.
    // Clear the temporary password on any form that remains in this document.
    setValue.call(fresh.password, '');
    setValue.call(fresh.account, oldAccount);
  }
}
