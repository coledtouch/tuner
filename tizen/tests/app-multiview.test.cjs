'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const appSource = fs.readFileSync(path.join(__dirname, '../js/app.js'), 'utf8');

// A DOM and player boundary fixture runs the real remote handlers and rendering code.
// It neither simulates video decoding nor changes any saved provider configuration.
function harness() {
  let now = 1000, timerId = 0;
  const timers = new Map(), documentEvents = {}, windowEvents = {};
  const document = { hidden: false };
  class Element {
    constructor(tag) {
      this.tagName = tag.toUpperCase(); this.children = []; this.parentNode = null;
      this.className = ''; this.id = ''; this.style = {}; this.attributes = {};
      this.value = ''; this.disabled = false; this.scrollLeft = 0; this.scrollTop = 0; this._text = '';
    }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    set textContent(value) {
      if (this.contains(document.activeElement)) document.activeElement = document.body;
      this.children.forEach(child => { child.parentNode = null; }); this.children = []; this._text = String(value);
    }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    contains(node) { return !!node && (node === this || this.children.some(child => child.contains(node))); }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) { return this.attributes[name] || null; }
    focus() { assert.ok(this.offsetWidth > 0, 'A hidden control must not receive focus'); document.activeElement = this; if (this.onfocus) this.onfocus(); }
    blur() { if (document.activeElement === this) document.activeElement = document.body; }
    click() { if (!this.disabled && this.onclick) this.onclick({ target: this }); }
    get offsetWidth() {
      for (let node = this; node; node = node.parentNode) {
        if (node.className.split(/\s+/).includes('hidden') || node.style.display === 'none') return 0;
        if (node.id === 'topbar' && document.body.className.includes('multiview-fullscreen')) return 0;
      }
      return this.id === 'main' ? 1920 : 200;
    }
    get offsetHeight() { return this.offsetWidth ? 60 : 0; }
    get offsetLeft() { return 0; }
    get offsetTop() { return 0; }
    get clientWidth() { return 1920; }
    get clientHeight() { return 1080; }
    getBoundingClientRect() { return { left: 1398, top: 145, width: 466, height: 263 }; }
    querySelectorAll(selector) {
      const parts = selector.split(',').map(value => value.trim()); const results = [];
      function matches(node, value) {
        if (value.startsWith('#')) return node.id === value.slice(1);
        if (value.startsWith('.')) return node.className.split(/\s+/).includes(value.slice(1));
        return node.tagName.toLowerCase() === value;
      }
      function visit(node) {
        for (const child of node.children) {
          if (parts.some(part => matches(child, part))) results.push(child);
          visit(child);
        }
      }
      visit(this); return results;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  }
  document.body = new Element('body'); document.activeElement = document.body;
  document.createElement = tag => new Element(tag);
  document.createTextNode = value => { const node = new Element('text'); node.textContent = value; return node; };
  document.getElementById = id => document.body.id === id ? document.body : document.body.querySelector('#' + id);
  document.addEventListener = (type, fn) => { (documentEvents[type] ||= []).push(fn); };
  for (const id of ['background', 'av-player', 'html-player', 'dual-object-0', 'dual-object-1', 'dual-video-0', 'dual-video-1', 'app']) {
    const node = new Element(id.includes('object') || id === 'av-player' ? 'object' : id.includes('video') || id === 'html-player' ? 'video' : 'div');
    node.id = id; document.body.appendChild(node);
  }
  for (const id of ['topbar', 'main', 'player-overlay', 'dialog-layer', 'toast']) {
    const node = new Element(id === 'main' ? 'main' : 'div'); node.id = id;
    if (id.includes('layer') || id.includes('overlay') || id === 'toast') node.className = 'hidden';
    document.getElementById('app').appendChild(node);
  }
  const channels = ['ESPN', 'NFL Network', 'CBS Sports'].map((name, index) => ({
    id: 'live:' + index, name, number: index + 1, categoryIds: [], url: 'https://fixture.invalid/' + index + '.ts', backups: []
  }));
  const settings = { source: { type: 'xtream', server: 'https://fixture.invalid', username: 'fixture', password: 'fixture' }, theme: 'black', zip: '' };
  const store = {
    settings, lastWatched() { return null; }, favourites() { return []; }, isFavourite() { return false; }, mostWatched() { return []; },
    recents() { return []; }, recordWatched() {}, saveRecent() {}, saveSettings(change) { Object.assign(settings, change); return true; }
  };
  const playerCalls = [], dualCalls = [], rects = [];
  const single = {
    state: { status: 'idle' }, stop() { this.state = { status: 'idle' }; playerCalls.push('stop'); }, setRect() {},
    suspend() { playerCalls.push('suspend'); }, restore() { playerCalls.push('restore'); },
    play(request) { this.state = { status: 'playing', request }; playerCalls.push(['play', request.id]); }
  };
  let dualOptions, clientOptions, dualCreates = 0;
  const dual = {
    play(slot, request) { dualCalls.push(['play', slot, request.id]); dualOptions.onState(slot, { status: 'playing', request }); },
    setRects(value) { rects.push(JSON.parse(JSON.stringify(value))); },
    selectAudio(slot) { dualCalls.push(['audio', slot]); return true; },
    stop() { dualCalls.push(['stop']); }, suspend() { dualCalls.push(['suspend']); }, restore() { dualCalls.push(['restore']); }
  };
  const registeredKeys = [];
  const window = {
    innerWidth: 1920, innerHeight: 1080, webapis: { avplay: {} },
    TunerData: {
      createStore() { return store; }, catalogIdentity() { return 'fixture'; },
      createClient(source, options) {
        clientOptions = options;
        return { catalogIdentity: 'fixture', cancel() {}, guide() { return { then() {} }; }, load() {
          options.onCatalog('live', channels, []); options.onStatus('live', { state: 'ready' });
        } };
      }
    },
    TunerPlayer: { create() { return single; }, createDual(options) { dualCreates++; dualOptions = options; return dual; } },
    tizen: { tvinputdevice: { getSupportedKeys() { return [{ name: 'Menu', code: 555 }, { name: 'Tools', code: 777 }]; }, registerKey(name) { registeredKeys.push(name); } } },
    addEventListener(type, fn) { (windowEvents[type] ||= []).push(fn); }
  };
  class FixtureDate extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } }
  const context = {
    window, document, Date: FixtureDate,
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, at: now + delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return ++timerId; }
  };
  vm.runInNewContext(appSource, context, { filename: 'app.js' });
  function node(id) { const result = document.getElementById(id); assert.ok(result, id + ' exists'); return result; }
  function emit(type, code) {
    const event = { keyCode: code, target: document.activeElement, preventDefault() {}, repeat: false };
    (documentEvents[type] || []).forEach(fn => fn(event));
  }
  function key(code) { emit('keydown', code); emit('keyup', code); }
  function advance(ms) {
    const end = now + ms;
    while (true) {
      const next = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      now = next[1].at; timers.delete(next[0]); next[1].fn();
    }
    now = end;
  }
  function pick(slot, index) {
    node('dual-pick-' + slot).click(); node('dual-result-' + index).click();
  }
  function start() { node('tab-5').click(); pick(0, 0); pick(1, 1); node('dual-start').click(); }
  return {
    document, node, key, advance, start, pick, dualCalls, playerCalls, rects, registeredKeys,
    state(slot, value) { dualOptions.onState(slot, value); },
    visibility(hidden) { document.hidden = hidden; (documentEvents.visibilitychange || []).forEach(fn => fn()); },
    unload() { (windowEvents.beforeunload || []).forEach(fn => fn()); },
    refresh() { clientOptions.onCatalog('live', channels.slice(), []); },
    get creates() { return dualCreates; }
  };
}

test('active Multi View fills logical viewport and auto-hides its chrome without recreating players', () => {
  const h = harness(), objects = [h.node('dual-object-0'), h.node('dual-object-1')]; h.start();
  assert.match(h.document.body.className, /multiview-fullscreen/);
  assert.equal(h.node('topbar').offsetWidth, 0);
  assert.deepEqual(h.rects.at(-1), [{ left: 0, top: 270, width: 960, height: 540 }, { left: 960, top: 270, width: 960, height: 540 }]);
  assert.equal(h.node('main').querySelectorAll('h1').length, 0);
  h.advance(5000);
  assert.match(h.node('dual-tray').className, /hidden/);
  assert.equal(h.document.activeElement.id, 'main');
  assert.equal(h.node('dual-audio-0').offsetWidth, 0);
  assert.ok(h.node('dual-frame-0').querySelector('.dual-label').className.includes('hidden'));
  assert.equal(h.creates, 1); assert.equal(h.dualCalls.filter(call => call[0] === 'play').length, 2);
  assert.deepEqual(objects, [h.node('dual-object-0'), h.node('dual-object-1')]);
});

test('OK, arrows and capability-reported Menu reveal controls and first OK only reveals', () => {
  const h = harness(); h.start(); h.advance(5000); h.key(13);
  assert.doesNotMatch(h.node('dual-tray').className, /hidden/);
  assert.equal(h.document.activeElement.id, 'dual-pick-0');
  assert.equal(h.node('dialog-layer').className, 'hidden');
  h.advance(5000); h.key(39); assert.equal(h.document.activeElement.id, 'dual-pick-0');
  h.key(39); assert.equal(h.document.activeElement.id, 'dual-pick-1');
  h.advance(5000); h.key(555); assert.doesNotMatch(h.node('dual-tray').className, /hidden/);
  assert.deepEqual(h.registeredKeys, ['Menu', 'Tools']);
  assert.equal(h.dualCalls.filter(call => call[0] === 'play').length, 2);
});

test('layout and selected audio change without reopening either feed', () => {
  const h = harness(); h.start(); const before = h.dualCalls.filter(call => call[0] === 'play');
  h.node('dual-layout').click();
  assert.deepEqual(h.rects.at(-1), [{ left: 0, top: 0, width: 1920, height: 1080 }, { left: 1312, top: 32, width: 576, height: 324 }]);
  h.node('dual-audio-1').click(); assert.match(h.node('dual-audio-1').className, /active/);
  assert.deepEqual(h.dualCalls.at(-1), ['audio', 1]);
  h.node('dual-layout').click(); assert.equal(h.rects.at(-1)[1].left, 960);
  assert.deepEqual(h.dualCalls.filter(call => call[0] === 'play'), before);
});

test('picker stays open past hide deadline, same channel keeps playback, replacement changes only one slot', () => {
  const h = harness(); h.start(); h.node('dual-pick-1').click(); h.advance(6000);
  assert.equal(h.node('dialog-layer').className, '');
  h.node('dual-result-1').click(); assert.equal(h.dualCalls.filter(call => call[0] === 'play').length, 2);
  assert.equal(h.document.activeElement.id, 'dual-pick-1');
  h.node('dual-pick-1').click(); h.node('dual-result-2').click();
  assert.deepEqual(h.dualCalls.filter(call => call[0] === 'play'), [['play', 0, 'live:0'], ['play', 1, 'live:1'], ['play', 1, 'live:2']]);
  h.advance(5000); assert.match(h.node('dual-tray').className, /hidden/);
});

test('Back hides visible controls then releases both games and returns to Live TV', () => {
  const h = harness(); h.start(); h.key(10009);
  assert.match(h.node('dual-tray').className, /hidden/); assert.equal(h.dualCalls.filter(call => call[0] === 'stop').length, 0);
  h.key(10009); assert.doesNotMatch(h.document.body.className, /multiview-fullscreen/);
  assert.equal(h.dualCalls.filter(call => call[0] === 'stop').length, 1);
  assert.equal(h.document.getElementById('dual-tray'), null);
  assert.ok(h.node('topbar').offsetWidth > 0); assert.ok(h.node('preview-frame'));
  assert.equal(h.playerCalls.filter(call => Array.isArray(call) && call[0] === 'play').length, 0);
});

test('buffering callbacks keep controls hidden and lifecycle still suspends and releases the same two players', () => {
  const h = harness(); h.start(); h.advance(5000); h.state(1, { status: 'loading' });
  assert.match(h.node('dual-tray').className, /hidden/);
  assert.match(h.node('dual-frame-1').textContent, /Connecting/);
  h.visibility(true); assert.deepEqual(h.dualCalls.at(-1), ['suspend']);
  h.advance(20000); h.visibility(false); assert.deepEqual(h.dualCalls.at(-1), ['restore']);
  assert.match(h.node('dual-tray').className, /hidden/);
  h.unload(); assert.deepEqual(h.dualCalls.at(-1), ['stop']); assert.equal(h.playerCalls.at(-1), 'stop');
  assert.equal(h.creates, 1);
});
