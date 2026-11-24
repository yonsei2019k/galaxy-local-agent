function (request) {
  "use strict";

  const ownerDocument = document;
  const selector = 'a[href],button,input:not([type=hidden]),select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=option],[onclick],[contenteditable=true]';
  const maxElements = 120;
  const maxScanned = 4096;
  const maxText = 12000;
  const fail = error => ({error});
  const textOf = e => (e.getAttribute('aria-label') ||
    (e.labels && e.labels.length ? Array.from(e.labels).map(l => l.innerText).join(' ') : '') ||
    e.innerText || (e.value && e.type !== 'password' && e.type !== 'hidden' && e.value) ||
    e.placeholder || e.title || e.alt || '').replace(/\s+/g, ' ').trim().slice(0, 120);
  const visible = e => {
    const r = e.getBoundingClientRect();
    const view = e.ownerDocument.defaultView;
    if (!view) return false;
    const s = view.getComputedStyle(e);
    return r.width > 1 && r.height > 1 && s.visibility !== 'hidden' && s.display !== 'none' &&
      r.bottom > 0 && r.top < view.innerHeight * 3;
  };

  // Check that a human pointer would reach this control in the current viewport.
  const receivesClick = node => {
    if (node.closest('[inert],[aria-disabled=true]')) return false;
    const rect = node.getBoundingClientRect();
    const view = ownerDocument.defaultView;
    const left = Math.max(0, rect.left);
    const top = Math.max(0, rect.top);
    const right = Math.min(view.innerWidth, rect.right);
    const bottom = Math.min(view.innerHeight, rect.bottom);
    if (right - left <= 1 || bottom - top <= 1) return false;
    const hit = ownerDocument.elementFromPoint((left + right) / 2, (top + bottom) / 2);
    return hit !== null && node.contains(hit);
  };

  const describe = (node, ref) => {
    const tag = node.tagName.toLowerCase();
    const form = node.form || null;
    const options = tag === 'select' ? Array.from(node.options).slice(0, 30) : [];
    const item = {
      ref, tag, role: node.getAttribute('role') || '', type: (node.getAttribute('type') || '').toLowerCase(),
      label: textOf(node), name: node.name || '', id: node.id || '', placeholder: node.placeholder || '',
      autocomplete: node.autocomplete || '', disabled: !!node.disabled || node.matches(':disabled,[aria-disabled=true]'),
      editable: node.isContentEditable || ['input','textarea','select'].includes(tag),
    };
    if (tag === 'a' && !item.disabled && !item.editable && !node.hasAttribute('download') &&
        !node.hasAttribute('ping') && !node.hasAttribute('onclick') && !node.closest('form') &&
        (!node.target || node.target === '_self') && (!item.role || item.role === 'link')) {
      item.ruleHref = node.href;
    }
    const row = node.closest('tr,[role=row]');
    const nearby = (row?.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 144);
    if (nearby) item.nearby = nearby;
    if (tag === 'select') item.options = options.map(o => o.text.trim().slice(0, 60));
    // The host receives only eligible link destinations and keeps them out of the model view.
    const binding = JSON.stringify([item, node.href, node.target, node.type, node.formAction, node.formMethod,
      node.formTarget, form && [form.action, form.method, form.target],
      options.map(o => [o.value, o.matches(':disabled')])]);
    return {node, form, options, item, binding};
  };

  const page = () => {
    const text = document.body ? document.body.innerText : '';
    return {
      url: location.href, title: document.title.slice(0, 200),
      text: text.replace(/\n{3,}/g, '\n\n').slice(0, maxText), truncated: text.length > maxText,
    };
  };

  const collect = () => {
    const content = [];
    const surrounding = [];
    const nodes = document.querySelectorAll(selector);
    let omitted = nodes.length > maxScanned;
    for (let i = 0; i < Math.min(nodes.length, maxScanned); i++) {
      const node = nodes[i];
      if (!visible(node)) continue;
      const main = node.closest('main,[role=main],article,tbody,[role=row]');
      const bucket = main ? content : surrounding;
      if (bucket.length < maxElements) bucket.push(node);
      else omitted = true;
    }
    const ordered = content.concat(surrounding);
    const controls = ordered.slice(0, maxElements).map((node, i) => describe(node, 'e' + (i + 1)));
    const scroll = document.scrollingElement;
    const viewport = ownerDocument.defaultView.innerHeight;
    const navigation = {
      canScrollUp: !!scroll && scroll.scrollTop > 2,
      canScrollDown: !!scroll && scroll.scrollHeight - scroll.scrollTop - viewport > 2,
      controlsIncomplete: omitted || ordered.length > maxElements,
    };
    return {page: page(), controls, navigation};
  };

  const observationControl = ({node, item}) => {
    const rect = node.getBoundingClientRect();
    return {...item,
      ...(rect.top >= ownerDocument.defaultView.innerHeight ? {position: 'below_viewport'} : {})};
  };

  const sameControl = (a, b) => a.node === b.node && a.form === b.form && a.binding === b.binding &&
    a.options.length === b.options.length && a.options.every((node, i) => node === b.options[i]);

  if (!Object.prototype.hasOwnProperty.call(ownerDocument, request.key)) {
    if (request.action !== 'observe') return fail('SNAPSHOT_STALE');
    let observed;
    const mediaPlayers = new WeakMap();
    let nextMediaId = 0;
    const mediaEvidence = () => {
      const candidates = [];
      const documents = [{doc: ownerDocument, depth: 0}];
      let frames = 0;
      let inaccessible = false;
      for (const {doc, depth} of documents) {
        const media = doc.querySelectorAll('video,audio');
        if (media.length > 32) return {status: 'media_scan_limit'};
        for (const node of media) {
          if (node instanceof doc.defaultView.HTMLMediaElement && node.isConnected &&
              (node.tagName.toLowerCase() === 'audio' || visible(node)) && node.currentSrc) {
            candidates.push(node);
            if (candidates.length > 1) return {status: 'multiple_players'};
          }
        }
        for (const frame of doc.querySelectorAll('iframe,frame')) {
          if (++frames > 12) return {status: 'media_scan_limit'};
          if (!frame.isConnected || !visible(frame)) continue;
          if (depth >= 3) return {status: 'media_scan_limit'};
          const child = frame.contentDocument;
          if (child?.defaultView) documents.push({doc: child, depth: depth + 1});
          else inaccessible = true;
        }
      }
      if (!candidates.length) return {status: inaccessible ? 'frame_unavailable' : 'player_unavailable'};
      const player = candidates[0];
      let identity = mediaPlayers.get(player);
      if (!identity) {
        identity = {id: request.snapshotId + ':' + ++nextMediaId, epoch: 0, source: player.currentSrc};
        for (const event of ['seeking', 'emptied', 'loadstart'])
          player.addEventListener(event, () => { identity.epoch++; });
        mediaPlayers.set(player, identity);
      }
      if (identity.source !== player.currentSrc) {
        identity.epoch++;
        identity.source = player.currentSrc;
      }
      const millis = value => Number.isFinite(value) && value >= 0 && value <= 86400 ? Math.round(value * 1000) : null;
      return {status: 'observed', playerId: identity.id, epoch: identity.epoch,
        positionMs: millis(player.currentTime), durationMs: millis(player.duration),
        paused: player.paused, ended: player.ended, seeking: player.seeking, readyState: player.readyState,
        rate: Number.isFinite(player.playbackRate) ? player.playbackRate : null};
    };
    const dispatch = input => {
      if (input.action === 'observe') {
        // Detect an owner's password form before collecting any page text or field values.
        if ([...ownerDocument.querySelectorAll('input[type=password]')].some(visible)) {
          observed = null;
          return {url: location.href, loginRequired: true};
        }
        observed = {id: input.snapshotId, ...collect()};
        return {...observed.page, elements: observed.controls.map(observationControl),
          navigation: observed.navigation,
          truncated: observed.page.truncated || observed.navigation.controlsIncomplete,
          ...(input.observeMedia ? {media: mediaEvidence()} : {})};
      }
      const expected = observed;
      if (!expected || expected.id !== input.snapshotId) return fail('SNAPSHOT_STALE');
      observed = null;
      if ((input.needsApproval || input.approvedSnapshot !== null) && input.approvedSnapshot !== expected.id)
        return fail('APPROVAL_MISMATCH');
      const current = collect();
      const pageBinding = JSON.stringify(expected.page);
      if (document !== ownerDocument || JSON.stringify(current.page) !== pageBinding ||
          expected.controls.length !== current.controls.length ||
          !expected.controls.every((control, i) => sameControl(control, current.controls[i])))
        return fail('SNAPSHOT_STALE');

      if (input.action === 'scroll') {
        window.scrollBy(0, input.direction === 'up' ? -700 : 700);
        return {performed: true};
      }
      if (input.action === 'back') {
        history.back();
        return {performed: true};
      }

      const control = expected.controls.find(e => e.item.ref === input.ref);
      if (!control || control.item.disabled) return fail('ELEMENT_UNAVAILABLE');
      const e = control.node;
      // Scrolling or focusing can synchronously invoke page code. Recheck before dispatch as well.
      const stillCurrent = () => document === ownerDocument && e.getRootNode() === ownerDocument &&
        e.matches(selector) && visible(e) && JSON.stringify(page()) === pageBinding &&
        sameControl(control, describe(e, input.ref));
      switch (input.action) {
        case 'click':
          e.scrollIntoView({block:'center', inline:'nearest', behavior:'instant'});
          if (!stillCurrent()) return fail('SNAPSHOT_STALE');
          if (!receivesClick(e)) return fail('ELEMENT_OBSCURED');
          if (input.ruleHref !== null && input.ruleHref !== undefined) {
            if (control.item.ruleHref !== input.ruleHref)
              return fail('SNAPSHOT_STALE');
            location.assign(input.ruleHref);
            break;
          }
          e.click();
          break;
        case 'type': {
          e.scrollIntoView({block:'center'});
          e.focus();
          if (!stillCurrent()) return fail('SNAPSHOT_STALE');
          if (e.isContentEditable) e.innerText = input.text;
          else {
            const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, input.text);
          }
          e.dispatchEvent(new Event('input', {bubbles:true}));
          e.dispatchEvent(new Event('change', {bubbles:true}));
          break;
        }
        case 'select': {
          const index = control.item.options.indexOf(input.text);
          if (index < 0 || control.options[index].matches(':disabled')) return fail('OPTION_UNKNOWN');
          e.selectedIndex = index;
          e.dispatchEvent(new Event('change', {bubbles:true}));
          break;
        }
        case 'press_enter':
          e.focus();
          if (!stillCurrent()) return fail('SNAPSHOT_STALE');
          for (const event of ['keydown','keypress','keyup'])
            e.dispatchEvent(new KeyboardEvent(event, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
          if (control.form && !control.form.querySelector('[type=submit]')) {
            // A key listener may already have executed. A changed form cannot receive a fallback submission.
            if (!stillCurrent()) return fail('ACTION_OUTCOME_UNKNOWN');
            control.form.requestSubmit();
          }
          break;
        default:
          return fail('ACTION_UNKNOWN');
      }
      return {performed: true};
    };
    Object.defineProperty(ownerDocument, request.key, {value: dispatch});
  }
  return ownerDocument[request.key](request);
}
