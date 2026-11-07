// Exercise signed-in workspace journeys with synthetic, account-scoped API data.
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { registerHooks } from 'node:module';
import ts from 'typescript';
import { JSDOM } from 'jsdom';

registerHooks({
  resolve(specifier, context, next) {
    try { return next(specifier, context); }
    catch (error) {
      if (specifier.startsWith('.') && context.parentURL) for (const extension of ['.ts', '.tsx']) {
        const url = new URL(specifier + extension, context.parentURL);
        if (fs.existsSync(fileURLToPath(url))) return next(url.href, context);
      }
      throw error;
    }
  },
  load(url, context, next) {
    if (url.startsWith('file:') && /\/src\/.*\.tsx?$/.test(url)) return {
      format: 'module', shortCircuit: true,
      source: ts.transpileModule(fs.readFileSync(fileURLToPath(url), 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
      }).outputText,
    };
    return next(url, context);
  },
});

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
for (const key of ['window', 'document', 'HTMLElement', 'HTMLDialogElement', 'HTMLInputElement', 'Event', 'CustomEvent']) globalThis[key] = dom.window[key];
globalThis.sessionStorage = window.sessionStorage;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// JSDOM does not implement native modal rendering or focus containment.
HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
HTMLElement.prototype.scrollIntoView = function () {};
window.scrollTo = function () {};
const mediaQueries = new Map();
window.matchMedia = query => {
  if (!mediaQueries.has(query)) mediaQueries.set(query, Object.assign(new window.EventTarget(), { media: query, matches: false }));
  return mediaQueries.get(query);
};

const { createElement, act, StrictMode } = await import('react');
const { createRoot } = await import('react-dom/client');
const { default: AuthGate } = await import('../src/components/AuthGate.tsx');
const { ThemeProvider } = await import('../src/theme/ThemeProvider.tsx');
const { offsetDate } = await import('../src/domain/application.ts');
const originalFetch = globalThis.fetch;
const account = { id: 'workspace-test-account', displayName: 'Ada Student', email: 'ada@example.test' };
const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
function fixtures() {
  const record = (id, company, status, patch = {}) => ({
    id, company, role: 'Engineering Intern', location: 'Remote', status,
    appliedDate: status === 'Saved' ? '' : offsetDate(-10), deadline: '', followUpDate: '', notes: '', stages: [],
    createdAt: '2026-01-01T12:00:00Z', updatedAt: `2026-01-0${id}T12:00:00Z`,
    version: 0, sample: false, deleted: false, ...patch,
  });
  return [
    record('1', 'Nimbus', 'Applied', { followUpDate: offsetDate(-1) }),
    record('2', 'Lumen', 'Interviewing', { notes: 'Prepare distributed systems questions.',
      stages: [{ id: 'lumen-round', name: 'Technical conversation', date: offsetDate(2), state: 'Scheduled' }] }),
    record('3', 'Atlas', 'Offer'),
    record('4', 'Sprout', 'Saved'),
    record('5', 'Quartz', 'Rejected', { followUpDate: offsetDate(-2),
      stages: [{ id: 'quartz-round', name: 'Cancelled conversation', date: offsetDate(1), state: 'Scheduled' }] }),
  ];
}

let root, container, requests;
async function render() {
  mediaQueries.clear();
  const records = fixtures();
  requests = [];
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), options });
    if (url === '/api/auth/me') return json(account);
    if (url === '/api/auth/csrf') return json({ token: 'workspace-test-csrf', headerName: 'X-CSRF-TOKEN' });
    assert.equal(new Headers(options.headers).get('X-Campus-Account'), account.id);
    assert.equal(options.credentials, 'same-origin');
    if (url === '/api/me/preferences') return json({ themePreference: options.method === 'PUT' ? JSON.parse(options.body).themePreference : 'system' });
    if (url === '/api/applications/history?size=100') return json({ content: records, nextCursor: null });
    throw new Error('Unexpected request: ' + url);
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(createElement(StrictMode, null, createElement(ThemeProvider, null, createElement(AuthGate)))));
  assert.equal(container.querySelector('[aria-label="Search applications"]')?.value, '');
  assert.equal(companies().length, records.length, 'the authenticated account history finished loading');
}
const text = element => [...element.childNodes].map(node => node.nodeType === 3 ? node.textContent : text(node)).join(' ').replace(/\s+/g, ' ').trim();
function button(name, scope = container) {
  const found = [...scope.querySelectorAll('button')].find(element => {
    const label = element.getAttribute('aria-label') ?? text(element);
    return typeof name === 'string' ? label === name : name.test(label);
  });
  assert.ok(found, `button is available: ${name}`);
  return found;
}
function field(name, scope = container) {
  const label = [...scope.querySelectorAll('label')].find(element => name.test(text(element)));
  assert.ok(label?.control, `label identifies a control: ${name}`);
  return label.control;
}
const companies = () => [...container.querySelectorAll('table tbody button[aria-label^="View "]')]
  .map(element => element.getAttribute('aria-label').replace(/^View (.*) details$/, '$1'));
const search = () => container.querySelector('input[aria-label="Search applications"]');
const workspace = () => container.querySelector('nav[aria-label="Workspace"]');
const summary = () => container.querySelector('[aria-label="Application summary"]');
const click = async element => act(async () => { element.click(); });
async function fill(element, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function choose(element, value) {
  await act(async () => { element.value = value; element.dispatchEvent(new Event('change', { bubbles: true })); });
}
afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = undefined; container?.remove(); window.localStorage.clear(); sessionStorage.clear();
  window.history.replaceState(null, '', '/'); globalThis.fetch = originalFetch;
});

test('workspace navigation resets search and shows only actionable interviews and follow-ups', async () => {
  await render();
  await fill(search(), 'Nimbus');
  assert.deepEqual(companies(), ['Nimbus']);
  await click(button(/^Interviews\b/, workspace()));
  assert.equal(search().value, '');
  assert.ok(button('Complete Lumen interview'));
  assert.equal(container.querySelector('[aria-label="Complete Quartz interview"]'), null, 'closed applications do not appear in the schedule');
  assert.equal(container.querySelector('table'), null);
  await click(button(/^Follow-ups\b/, workspace()));
  assert.ok(button('Complete Nimbus follow-up'));
  assert.equal(container.querySelector('[aria-label="Complete Quartz follow-up"]'), null);
  assert.equal(container.querySelector('[aria-label="Complete Lumen interview"]'), null);
  await fill(search(), 'Lumen');
  assert.match(container.textContent, /No matching items/);
  await click(button(/^Applications\b/, workspace()));
  assert.equal(search().value, '');
  assert.equal(companies().length, 5);
  assert.equal(container.querySelector('[role="tab"][aria-selected="true"]').textContent.replace(/\d+$/, '').trim(), 'All applications');
});

test('summary shortcuts, status selection, notes search, and sorting compose without losing records', async () => {
  await render();
  await click(button(/^In progress\b/, summary()));
  assert.deepEqual(companies(), ['Lumen', 'Nimbus']);
  await fill(search(), 'DISTRIBUTED SYSTEMS');
  assert.deepEqual(companies(), ['Lumen'], 'search includes notes and ignores case');
  await click(button('Clear search'));
  assert.deepEqual(companies(), ['Lumen', 'Nimbus']);
  await click(button(/^Offer\b/, container.querySelector('nav[aria-label="Application statuses"]')));
  assert.deepEqual(companies(), ['Atlas']);
  assert.equal(container.querySelector('[aria-label="Filter status"]').value, 'Offer');
  await click(button(/^Total applications\b/, summary()));
  await choose(container.querySelector('[aria-label="Sort applications"]'), 'company');
  assert.deepEqual(companies(), ['Atlas', 'Lumen', 'Nimbus', 'Quartz', 'Sprout']);
  await choose(container.querySelector('[aria-label="Filter status"]'), 'Saved');
  assert.deepEqual(companies(), ['Sprout']);
  await fill(search(), 'Nimbus');
  assert.deepEqual(companies(), []);
  assert.match(container.textContent, /No applications found/);
  await click(button('Clear filters'));
  assert.equal(search().value, '');
  assert.deepEqual(companies(), ['Atlas', 'Lumen', 'Nimbus', 'Quartz', 'Sprout']);
});

test('details preserve the saved application when editing is cancelled and new drafts start empty', async () => {
  await render();
  await click(button('View Lumen details'));
  let dialog = container.querySelector('dialog[open]');
  assert.equal(document.getElementById(dialog.getAttribute('aria-labelledby')).textContent, 'Lumen');
  assert.match(dialog.textContent, /Prepare distributed systems questions/);
  await click(button('Edit application', dialog));
  assert.equal(field(/^Company\b/, dialog).value, 'Lumen');
  await fill(field(/^Company\b/, dialog), 'Unsaved company');
  await click(button('Cancel', dialog));
  assert.equal(document.getElementById(dialog.getAttribute('aria-labelledby')).textContent, 'Lumen');
  await click(button('Close application', dialog));
  assert.equal(container.querySelector('dialog'), null);
  await click(button('Add application'));
  dialog = container.querySelector('dialog[open]');
  assert.equal(field(/^Company\b/, dialog).value, '');
  assert.equal(field(/^Status\b/, dialog).value, 'Saved');
  await fill(field(/^Company\b/, dialog), 'Unsubmitted opportunity');
  await click(button('Close application', dialog));
  await click(button('Add application'));
  dialog = container.querySelector('dialog[open]');
  assert.equal(field(/^Company\b/, dialog).value, '', 'closed unsaved drafts are not reused');
  await click(button('Cancel', dialog));
  assert.equal(container.querySelector('dialog'), null);
  assert.equal(companies().length, 5);
  assert.equal(requests.some(({ options }) => ['POST', 'PUT', 'DELETE'].includes(options.method)), false, 'opening and cancelling editors never submits an application');
});

test('CSV entry can be dismissed and appearance changes persist through the account API', async () => {
  await render();
  const entry = button('Import CSV');
  entry.focus();
  await click(entry);
  const dialog = container.querySelector('dialog[open]');
  assert.equal(document.getElementById(dialog.getAttribute('aria-labelledby')).textContent, 'Import applications from CSV');
  assert.equal(field(/^CSV file\b/, dialog).type, 'file');
  assert.equal(button('Read file and show columns', dialog).disabled, true);
  await click(button('Close', dialog));
  assert.equal(container.querySelector('dialog'), null);
  assert.equal(document.activeElement, entry);
  assert.equal(companies().length, 5);
  await choose(field(/^Appearance\b/), 'dark');
  assert.equal(document.documentElement.dataset.theme, 'dark');
  const write = requests.find(({ url, options }) => url === '/api/me/preferences' && options.method === 'PUT');
  assert.ok(write, 'appearance is saved to the signed-in account');
  assert.deepEqual(JSON.parse(write.options.body), { themePreference: 'dark' });
  assert.equal(new Headers(write.options.headers).get('X-CSRF-TOKEN'), 'workspace-test-csrf');
  assert.equal(requests.some(({ url }) => url.includes('/application-imports')), false, 'opening the import dialog does not create a preview');
});

test('skip to workspace moves focus to main without changing the current URL or filters', async () => {
  await render();
  await fill(search(), 'Nimbus');
  window.history.replaceState(null, '', '/#workspace');
  const skip = [...container.querySelectorAll('a')].find(element => text(element) === 'Skip to workspace');
  assert.ok(skip);
  const destination = document.getElementById(skip.hash.slice(1));
  assert.equal(destination?.tagName, 'MAIN');
  skip.focus();
  const event = new window.MouseEvent('click', { bubbles: true, cancelable: true });
  await act(async () => { skip.dispatchEvent(event); });
  assert.equal(event.defaultPrevented, true, 'the skip action does not start hash navigation');
  assert.equal(window.location.hash, '#workspace');
  assert.equal(document.activeElement, destination);
  assert.equal(search().value, 'Nimbus');
  assert.deepEqual(companies(), ['Nimbus']);
});

test('mobile navigation announces expansion and returns focus after close or Escape', async () => {
  await render();
  const toggle = button('Open navigation');
  const navigation = document.getElementById(toggle.getAttribute('aria-controls'));
  assert.ok(navigation?.contains(workspace()), 'the menu control identifies the workspace navigation');
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  await click(toggle);
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  assert.equal(document.activeElement, button(/^Applications\b/, workspace()), 'opening focuses the selected view');
  assert.ok(container.querySelector('.main-shell').hasAttribute('inert'), 'background controls are inactive while navigation is open');
  const controls = [...navigation.querySelectorAll('a[href], button:not(:disabled)')];
  controls.at(-1).focus();
  const forward = new window.KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  await act(async () => { controls.at(-1).dispatchEvent(forward); });
  assert.equal(forward.defaultPrevented, true);
  assert.equal(document.activeElement, controls[0]);
  const backward = new window.KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true });
  await act(async () => { controls[0].dispatchEvent(backward); });
  assert.equal(backward.defaultPrevented, true);
  assert.equal(document.activeElement, controls.at(-1));
  const searchShortcut = new window.KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, cancelable: true });
  await act(async () => { controls.at(-1).dispatchEvent(searchShortcut); });
  assert.equal(document.activeElement, controls.at(-1), 'the search shortcut does not move focus behind navigation');
  const close = button('Close navigation', navigation);
  close.focus();
  await click(close);
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(document.activeElement, toggle);
  assert.equal(container.querySelector('.main-shell').hasAttribute('inert'), false);
  await click(toggle);
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  const interviews = button(/^Interviews\b/, workspace());
  interviews.focus();
  await act(async () => { interviews.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(document.activeElement, toggle);
  assert.equal(companies().length, 5);
  await click(toggle);
  const desktop = window.matchMedia('(min-width: 801px)');
  desktop.matches = true;
  await act(async () => { desktop.dispatchEvent(Object.assign(new window.Event('change'), { matches: true })); });
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(container.querySelector('.main-shell').hasAttribute('inert'), false, 'desktop resizing releases the background');
  assert.equal(document.activeElement, document.getElementById('workspace-content'));
});
