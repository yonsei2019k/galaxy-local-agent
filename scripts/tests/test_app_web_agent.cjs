const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../public/app-web-agent.js'), 'utf8');

function fixture() {
  const effects = [];
  const location = {href: 'https://fixture.example.test/details', assign(url) { effects.push(url); }};
  const makeDocument = () => ({
    nodes: [],
    title: 'Fixture',
    body: {innerText: 'Synthetic details'},
    querySelectorAll() { return this.nodes; },
  });
  const context = vm.createContext({
    document: makeDocument(), location, innerHeight: 600,
    getComputedStyle: node => ({display: node.hidden ? 'none' : 'block', visibility: 'visible'}),
    window: {scrollBy(...position) { effects.push(position); }},
    history: {back() { effects.push('back'); }},
    Event: class { constructor(type) { this.type = type; } },
  });
  context.KeyboardEvent = context.Event;
  class Input {
    constructor(label, tag = 'BUTTON') {
      this.tagName = tag;
      this.innerText = label;
      this.type = tag === 'INPUT' ? 'text' : 'button';
      this.isConnected = true;
      this.ownerDocument = context.document;
      this.attrs = {};
      this._value = '';
    }
    getAttribute(name) { return this.attrs[name] ?? null; }
    hasAttribute(name) { return Object.hasOwn(this.attrs, name); }
    closest(tag) { return tag === 'form' ? this.enclosingForm ?? null : null; }
    matches(query) { return query.includes(':disabled') ? !!this.disabled : !this.noLongerControl; }
    getRootNode() { return this.isConnected ? this.ownerDocument : this; }
    getBoundingClientRect() { return {width: 80, height: 25, top: 10, bottom: 35}; }
    scrollIntoView() { this.onScroll?.(); }
    focus() { this.onFocus?.(); }
    click() { effects.push(this); }
    dispatchEvent(event) { effects.push(event.type); this.onEvent?.(event); }
    get value() { return this._value; }
    set value(value) { this._value = value; }
  }
  context.HTMLInputElement = context.HTMLTextAreaElement = Input;
  function invoke(input) {
    return vm.runInContext('(' + source + ')(' + JSON.stringify({
      key: 'fixture-binding', snapshotId: 'snapshot-1', ref: 'e1', approvedSnapshot: null,
      ...input,
    }) + ')', context);
  }
  const button = new Input('Open details');
  context.document.nodes = [button];
  return {context, effects, button, invoke, Input, makeDocument};
}

test('a navigation rule follows only the exact observed link and cannot replay it', () => {
  const {context, effects, invoke, Input} = fixture();
  const link = new Input('Product details','A');
  link.href = 'https://fixture.example.test/product';
  context.document.nodes = [link];
  assert.equal(invoke({action:'observe'}).elements[0].ruleHref,link.href);
  assert.equal(invoke({action:'click',ruleHref:link.href+'/other'}).error,'SNAPSHOT_STALE');
  invoke({action:'observe'});
  assert.equal(invoke({action:'click',ruleHref:link.href}).performed,true);
  assert.equal(invoke({action:'click',ruleHref:link.href}).error,'SNAPSHOT_STALE');
  assert.deepEqual(effects,[link.href]);
  link.attrs.download = '';
  assert.equal(invoke({action:'observe'}).elements[0].ruleHref,undefined);
  assert.equal(invoke({action:'click',ruleHref:link.href}).error,'SNAPSHOT_STALE');
  assert.deepEqual(effects,[link.href]);
});

test('a same-looking replacement after observation receives no click', () => {
  const {context, effects, button, invoke, Input} = fixture();
  assert.equal(invoke({action:'observe'}).elements[0].ref, 'e1');
  button.isConnected = false;
  context.document.nodes = [new Input(button.innerText)];
  assert.equal(invoke({action:'click'}).error, 'SNAPSHOT_STALE');
  assert.deepEqual(effects, []);
});

test('a changed URL, same-URL reload, or changed page text refuses navigation', () => {
  for (const change of [
    f => { f.context.location.href += '#next'; },
    f => { f.context.document = f.makeDocument(); f.context.document.nodes = [new f.Input(f.button.innerText)]; },
    f => { f.context.document.body.innerText = 'Different conditions'; },
  ]) {
    const f = fixture();
    f.invoke({action:'observe'});
    change(f);
    assert.equal(f.invoke({action:'back'}).error, 'SNAPSHOT_STALE');
    assert.deepEqual(f.effects, []);
  }
});

test('the same node changing destination or disabled state invalidates a snapshot', () => {
  for (const change of [
    e => { e.href = 'https://fixture.example.test/other'; },
    e => { e.disabled = true; },
  ]) {
    const {effects, button, invoke} = fixture();
    button.href = 'https://fixture.example.test/initial';
    invoke({action:'observe'});
    change(button);
    assert.equal(invoke({action:'click'}).error, 'SNAPSHOT_STALE');
    assert.deepEqual(effects, []);
  }
});

test('required approval binds the current snapshot and dispatches once', () => {
  const {effects, button, invoke} = fixture();
  for (const approvedSnapshot of [null, 'older-snapshot']) {
    invoke({action:'observe'});
    assert.equal(invoke({action:'click', needsApproval:true, approvedSnapshot}).error, 'APPROVAL_MISMATCH');
  }
  invoke({action:'observe'});
  assert.equal(invoke({action:'click', needsApproval:true, approvedSnapshot:'snapshot-1'}).performed, true);
  assert.equal(invoke({action:'click', needsApproval:true, approvedSnapshot:'snapshot-1'}).error, 'SNAPSHOT_STALE');
  assert.deepEqual(effects, [button]);
});

test('scrolling or focusing before dispatch cannot redirect the action', () => {
  for (const action of ['click', 'type', 'press_enter']) {
    const {context, effects, button, invoke, Input} = fixture();
    const node = action === 'click' ? button : new Input('Search', 'INPUT');
    context.document.nodes = [node];
    const replace = () => {
      node.isConnected = false;
      context.document.nodes = [new Input(node.innerText, node.tagName)];
    };
    if (action === 'click') node.onScroll = replace;
    else node.onFocus = replace;
    invoke({action:'observe'});
    assert.equal(invoke({action, text:'synthetic query'}).error, 'SNAPSHOT_STALE');
    assert.equal(node.value, '');
    assert.deepEqual(effects, []);
  }
});

test('the observed control can become hidden before the click is dispatched', () => {
  const {effects, button, invoke} = fixture();
  button.onScroll = () => { button.hidden = true; };
  invoke({action:'observe'});
  assert.equal(invoke({action:'click'}).error, 'SNAPSHOT_STALE');
  assert.deepEqual(effects, []);
});

test('option values are private to the comparison and a changed value cannot be selected', () => {
  const {context, effects, invoke, Input} = fixture();
  const select = new Input('Filter', 'SELECT');
  const option = {text: 'Newest', value: 'private-queue-id', matches() { return false; }};
  select.options = [option];
  context.document.nodes = [select];
  const snapshot = invoke({action:'observe'});
  assert.equal(snapshot.elements[0].options[0], 'Newest');
  assert.equal(JSON.stringify(snapshot).includes(option.value), false);
  option.value = 'different-queue-id';
  assert.equal(invoke({action:'select', text:'Newest'}).error, 'SNAPSHOT_STALE');
  assert.deepEqual(effects, []);
});

test('a key handler changing the form leaves its result unknown and skips fallback submit', () => {
  const {context, effects, invoke, Input} = fixture();
  const input = new Input('Search', 'INPUT');
  let fallback = false;
  input.form = {
    action: 'https://fixture.example.test/filters',
    querySelector() { return null; },
    requestSubmit() { fallback = true; },
  };
  input.onEvent = () => { input.form.action += '/next'; };
  context.document.nodes = [input];
  invoke({action:'observe'});
  assert.equal(invoke({action:'press_enter'}).error, 'ACTION_OUTCOME_UNKNOWN');
  assert.ok(effects.includes('keydown'));
  assert.equal(fallback, false);
});

test('typing and selection still dispatch against unchanged controls', () => {
  const {context, effects, invoke, Input} = fixture();
  const input = new Input('Search', 'INPUT');
  const select = new Input('Filter', 'SELECT');
  select.options = [{text: 'Newest', value: 'new', matches() { return false; }}];
  context.document.nodes = [input, select];
  invoke({action:'observe'});
  assert.equal(invoke({action:'type', text:'synthetic query'}).performed, true);
  assert.equal(input.value, 'synthetic query');
  invoke({action:'observe', snapshotId:'snapshot-2'});
  assert.equal(invoke({action:'select', snapshotId:'snapshot-2', ref:'e2', text:'Newest'}).performed, true);
  assert.equal(select.selectedIndex, 0);
  assert.deepEqual(effects, ['input', 'change', 'change']);
});

test('scroll and back operate on the unchanged observed page', () => {
  const {effects, invoke} = fixture();
  invoke({action:'observe'});
  assert.equal(invoke({action:'scroll', direction:'up'}).performed, true);
  invoke({action:'observe', snapshotId:'snapshot-2'});
  assert.equal(invoke({action:'back', snapshotId:'snapshot-2'}).performed, true);
  assert.deepEqual(effects, [[0, -700], 'back']);
});
