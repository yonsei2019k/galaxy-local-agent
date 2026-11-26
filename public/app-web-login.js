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

  if (request.action === 'authenticationState' || request.action === 'authenticatedTaskReady') {
    const checkingTask = request.action === 'authenticatedTaskReady';
    const unavailable = checkingTask ? false : 'unverified';
    if (location.origin !== request.origin || doc.readyState !== 'complete') return unavailable;
    const fields = [...doc.querySelectorAll('input')].filter(visible);
    if (fields.some(field => {
      const labelIds = (field.getAttribute('aria-labelledby') || '').trim().split(/\s+/).filter(Boolean).slice(0, 12);
      const label = [field.name, field.id, field.autocomplete, field.placeholder, field.getAttribute('aria-label'),
        ...labelIds.map(id => doc.getElementById(id)?.textContent),
        ...Array.from(field.labels || [], text => text.textContent || text.innerText)].join(' ');
      return field.type === 'password' ||
        /password|\b(?:otp|mfa|2fa)[a-z0-9_-]*|(?:auth(?:entication)?|verification|one[ -]?time|security)[\s_.-]*code|인증[\s_.-]*(?:번호|코드)|보안[\s_.-]*코드|일회용[\s_.-]*(?:번호|코드)/i.test(label);
    })) return unavailable;
    if (checkingTask) {
      const content = doc.querySelector('main,[role=main],article') || doc.body;
      if (!content || !visible(content) || content.closest('[aria-busy=true]') || !content.innerText.trim()) return false;
      const text = content.innerText.trim();
      if (text.length > 24_000) return false;
      const alerts = Array.from(doc.querySelectorAll('[role=alert],dialog[open]'))
        .filter(visible).map(node => node.innerText).join('\n');
      const failed = /로그인\s*(?:필요|해\s*주세요)|다시\s*로그인|^로그인$|^sign in$|sign in to|authentication required|본인\s*확인|추가\s*인증|2\s*단계\s*인증|접근\s*(?:제한|불가)|서비스를 이용하실 수 없|찾을\s*수\s*없|조회.{0,12}(?:실패|오류|불가)|다시\s*시도|access denied|service unavailable|not found|try again/im;
      if (failed.test(text) || failed.test(alerts)) return false;
      const loading = /불러\s*오는\s*중|로딩\s*중|잠시\s*기다려|\bloading\b/i;
      if (loading.test(text) || loading.test(alerts)) return false;
      const explicitResult = /^(?:(?:예약|주문|결제)이\s*확인됐어요|(?:예약|주문|결제|받은\s*메일)(?:\s*(?:내역|목록))?(?:이|가)?\s*(?:없습니다|없어요|0\s*건)|no\s+(?:reservations|bookings|orders|messages)(?:\s+found)?)[.!]?$/i;
      const status = /(?:예약|주문|배송|결제)\s*(?:확정|완료|취소|준비\s*중|대기|중)/;
      const reference = /(?:예약|주문|결제|배송)\s*번호(?:\s*[:：]\s*|\s+)(?=[A-Z0-9-]*\d)[A-Z0-9-]{3,}\b/i;
      const date = /\b\d{4}\s*[./-]\s*\d{1,2}\s*[./-]\s*\d{1,2}\b/;
      const nonResult = 'nav,aside,header,footer,form,button,a,[role=navigation],[role=button],[aria-busy=true],[hidden],[aria-hidden=true],[inert]';
      const result = content.querySelectorAll('div,p,dd,li,tr,[role=row],[role=listitem],[role=status]');
      return Array.from(result).some(node => {
        if (!visible(node) || node.closest(nonResult) || node.querySelector('th,[role=columnheader]')) return false;
        const value = node.innerText.trim().replace(/\s+/g, ' ');
        if (explicitResult.test(value) && !node.querySelector(nonResult)) return true;
        return node.matches('dd,li,tr,[role=row],[role=listitem]') &&
          status.test(value) && (reference.test(value) || date.test(value));
      });
    }
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
