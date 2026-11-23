const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../public/app-web-agent.js'), 'utf8');

function fixture() {
  const effects = [];
  const location = {href: 'https://fixture.example.test/details', assign(url) { effects.push(url); }};
  const view = {innerHeight: 600, getComputedStyle: node => ({display: node.hidden ? 'none' : 'block', visibility: 'visible'})};
  const makeDocument = () => ({
    nodes: [],
    title: 'Fixture',
    defaultView: view,
    body: {innerText: 'Synthetic details'},
    querySelectorAll(selector) {
      if (selector === 'video,audio') return this.nodes.filter(node => ['VIDEO','AUDIO'].includes(node.tagName));
      if (selector === 'iframe,frame') return this.nodes.filter(node => ['IFRAME','FRAME'].includes(node.tagName));
      if (selector === 'input[type=password]') return this.nodes.filter(node => node.tagName==='INPUT' && node.type==='password');
      return this.nodes.filter(node => !['IFRAME','FRAME'].includes(node.tagName));
    },
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
      this.listeners = {};
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
    addEventListener(name, listener) { this.listeners[name] = listener; }
    get value() { return this._value; }
    set value(value) { this._value = value; }
  }
  context.HTMLInputElement = context.HTMLTextAreaElement = Input;
  context.HTMLMediaElement = Input;
  view.HTMLMediaElement = Input;
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

test('reads the timeline of a static muted player without collecting audio or media URLs', () => {
  const {context, effects, invoke, Input} = fixture();
  const media = new Input('Static lesson','VIDEO');
  Object.assign(media,{currentSrc:'https://private.example.test/secret-stream',currentTime:1,
    duration:60,paused:false,ended:false,seeking:false,readyState:4,playbackRate:1,muted:true,volume:0});
  context.document.nodes=[media];
  const options = {action:'observe', observeMedia:true, snapshotId:'a'.repeat(32)};
  const first = invoke(options).media;
  media.currentTime=3;
  const second = invoke(options).media;
  assert.equal(first.playerId,second.playerId);
  assert.equal(second.positionMs-first.positionMs,2000);
  assert.equal(second.paused,false);
  assert.equal(JSON.stringify(second).includes(media.currentSrc),false);
  assert.equal('muted' in second,false);
  media.listeners.seeking();
  assert.equal(invoke(options).media.epoch,second.epoch+1);
  media.tagName='AUDIO';
  media.hidden=true;
  assert.equal(invoke(options).media.status,'observed');
  context.document.nodes.push(Object.assign(new Input('Other','AUDIO'),{currentSrc:media.currentSrc}));
  assert.equal(invoke(options).media.status,'multiple_players');
  assert.deepEqual(effects,[]);
});

test('a visible password form returns only authentication metadata', () => {
  const {context, effects, invoke, Input} = fixture();
  const user = new Input('', 'INPUT');
  user.value = 'fixture-account-never-in-snapshot';
  const secret = new Input('', 'INPUT');
  secret.type = 'password';
  secret.value = 'fixture-password-never-in-snapshot';
  context.document.nodes = [user, secret];
  const page = invoke({action:'observe',observeMedia:true});
  assert.equal(page.loginRequired,true);
  assert.equal(page.elements,undefined);
  assert.equal(page.text,undefined);
  assert.equal(JSON.stringify(page).includes(user.value),false);
  assert.equal(JSON.stringify(page).includes(secret.value),false);
  assert.equal(invoke({action:'click'}).error,'SNAPSHOT_STALE');
  assert.deepEqual(effects,[]);
});

test('observes a single same-origin frame player and invalidates replacement evidence', () => {
  const {context, effects, invoke, Input, makeDocument} = fixture();
  const frame = new Input('Lesson frame', 'IFRAME');
  const frameDocument = makeDocument();
  class FrameMedia extends Input {}
  frameDocument.defaultView = {...frameDocument.defaultView, HTMLMediaElement: FrameMedia};
  const makePlayer = () => Object.assign(new FrameMedia('', 'VIDEO'), {
    ownerDocument: frameDocument, currentSrc: 'https://fixture.example.test/private-stream',
    currentTime: 1, duration: 4, paused: false, ended: false, seeking: false, readyState: 4, playbackRate: 1,
  });
  const player = makePlayer();
  frame.contentDocument = frameDocument;
  frameDocument.nodes = [player];
  context.document.nodes = [frame];
  const read = () => invoke({action: 'observe', observeMedia: true, snapshotId: 'a'.repeat(32)}).media;
  const first = read();
  assert.equal(first.status, 'observed');
  player.currentTime = 4;
  player.ended = true;
  assert.equal(read().playerId, first.playerId);
  assert.equal(read().ended, true);
  assert.equal(JSON.stringify(read()).includes(player.currentSrc), false);
  frameDocument.nodes.push(makePlayer());
  assert.equal(read().status, 'multiple_players');
  frameDocument.nodes = [makePlayer()];
  assert.notEqual(read().playerId, first.playerId);
  frame.contentDocument = null;
  assert.equal(read().status, 'frame_unavailable');
  assert.deepEqual(effects, []);
});

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
