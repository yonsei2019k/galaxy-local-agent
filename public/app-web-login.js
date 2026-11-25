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

  if (request.action === 'authenticationState') {
    if (location.origin !== request.origin || doc.readyState !== 'complete') return 'unverified';
    const fields = [...doc.querySelectorAll('input')].filter(visible);
    if (fields.some(field => {
      const labelIds = (field.getAttribute('aria-labelledby') || '').trim().split(/\s+/).filter(Boolean).slice(0, 12);
      const label = [field.name, field.id, field.autocomplete, field.placeholder, field.getAttribute('aria-label'),
        ...labelIds.map(id => doc.getElementById(id)?.textContent),
        ...Array.from(field.labels || [], text => text.textContent || text.innerText)].join(' ');
      return field.type === 'password' ||
        /password|\b(?:otp|mfa|2fa)[a-z0-9_-]*|(?:auth(?:entication)?|verification|one[ -]?time|security)[\s_.-]*code|인증[\s_.-]*(?:번호|코드)|보안[\s_.-]*코드|일회용[\s_.-]*(?:번호|코드)/i.test(label);
    })) return 'unverified';
    const controls = [...doc.querySelectorAll('a[href],button,[role=button],[role=menuitem]')];
    const name = node => (node.getAttribute('aria-label') || node.innerText || node.textContent || '').trim();
    const isSignOut = node => /^(?:로그아웃|sign out|log out)$/i.test(name(node));
    if (controls.some(node => visible(node) && !node.closest('[hidden],[aria-hidden=true]') && isSignOut(node))) return 'signed_in';
    const accountMenu = controls.some(node => {
      if (!visible(node)) return false;
      if (/^(?:내 계정|계정 메뉴|프로필 메뉴|my account|account menu|profile menu)$/i.test(name(node))) return true;
      if (node.getAttribute('aria-expanded') !== 'false') return false;
      const menu = doc.getElementById(node.getAttribute('aria-controls') || '');
      return menu && controls.some(control => isSignOut(control) && menu.contains(control));
    });
    return accountMenu ? 'account_menu' : 'unverified';
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
