// Exercise real drawer/field handlers, description hook, and API serialization with controlled HTTP reads.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import * as application from '../src/domain/application.ts';
import * as postingDetails from '../src/domain/postingDetails.ts';
import {applicationApi} from '../src/data/applicationApi.ts';
import {postingApi} from '../src/data/postingApi.ts';
import {ApiError} from '../src/data/api.ts';
import {createReactHost, findNode, nodeText} from './helpers/reactHost.mjs';

const compiled = Object.fromEntries(await Promise.all([
  'components/ApplicationDrawer.tsx', 'components/PostingFields.tsx', 'hooks/usePostingDescription.ts',
].map(async path => [path, ts.transpileModule(await readFile(new URL('../src/' + path, import.meta.url), 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX},
}).outputText])));

function load(path, modules) {
  const module = {exports: {}};
  new Function('require', 'module', 'exports', compiled[path])(name => {
    assert.ok(name in modules, 'Unmapped import ' + name);return modules[name];
  }, module, module.exports);
  return module.exports;
}

const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: {'Content-Type': 'application/json'},
});
const tick = () => new Promise(resolve => setImmediate(resolve));
const record = (overrides = {}) => ({
  id: 'A', company: 'Example', role: 'Intern', location: 'Remote', status: 'Saved',
  appliedDate: '', deadline: '', followUpDate: '', notes: 'Saved notes', stages: [],
  createdAt: '2026-10-02T08:00:00Z', updatedAt: '2026-10-03T12:00:00Z', version: 7, sample: false, deleted: false,
  postingDetails: {...postingDetails.blankPostingDetails(), recruiterName: 'Saved recruiter',
    compensationMin: '20.00', compensationCurrency: 'USD', compensationPeriod: 'HOUR',
    compensationStatus: 'PAID', descriptionRevision: 3}, ...overrides,
});
const envelope = (applicationVersion = 7, descriptionText = 'Saved description') => ({applicationVersion,
  snapshot: {id: 'snapshot-3', revision: 3, sourceUrl: 'https://example.test/job',
    capturedAt: '2026-10-03T12:00:00Z', captureMethod: 'MANUAL', descriptionText},
});

function drawer(t) {
  const runtime = createReactHost(), requests = [];
  const originalFetch = globalThis.fetch;
  // Delay at fetch, retaining real GET adapters, error decoding, CSRF handling, and write serializers.
  // Intentionally permit responses after abort so missing component guards remain observable.
  globalThis.fetch = (url, options) => url === '/api/auth/csrf'
    ? Promise.resolve(json({token: 'test-csrf', headerName: 'X-CSRF-TOKEN'}))
    : new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
  t.after(() => {runtime.unmount();globalThis.fetch = originalFetch;});
  const jsx = (type, props, key) => ({type, props, key});
  const modules = {react: runtime.hooks, 'react/jsx-runtime': {jsx, jsxs: jsx},
    'lucide-react': new Proxy({}, {get: (_, name) => String(name)}),
    '../domain/application': application, '../domain/postingDetails': postingDetails,
    '../data/api': {ApiError}, '../data/applicationApi': {applicationApi}, '../data/postingApi': {postingApi},
  };
  const {usePostingDescription} = load('hooks/usePostingDescription.ts', modules);
  const {PostingFields} = load('components/PostingFields.tsx', modules);
  const {ApplicationDrawer} = load('components/ApplicationDrawer.tsx', {...modules,
    '../hooks/usePostingDescription': {usePostingDescription}, './PostingFields': {PostingFields},
    './PostingDetailsPanel': {PostingDetailsPanel: 'PostingDetailsPanel'},
    './Shared': {CompanyMark: 'CompanyMark', StatusBadge: 'StatusBadge'},
  });
  const props = {application: record(), isNew: false, onClose() {}, onDelete: async () => {},
    onSave: applicationApi.save, onRefresh: async () => {}};
  let tree;
  function expand(node) {
    if (Array.isArray(node)) return node.map(expand);
    if (!node || typeof node !== 'object') return node;
    if (node.type === PostingFields) return expand(PostingFields(node.props));
    return {...node, props: {...node.props, children: expand(node.props?.children)}};
  }
  function flush() {
    for (let i = 0; i < 20; i++) {
      tree = expand(runtime.render(() => ApplicationDrawer(props)));runtime.commit();
      if (!runtime.dirty) return;
    }
    assert.fail('Drawer did not settle');
  }
  const settle = async () => {await tick();flush();};
  const matching = (path, method = 'GET') => requests.filter(request =>
    request.url === '/api/applications/A' + path && (request.options.method ?? 'GET') === method);
  const button = name => findNode(tree, node => node.type === 'button' && nodeText(node).trim() === name);
  const field = name => {
    const label = findNode(tree, node => node.type === 'label' && nodeText(node).trim() === name);
    const input = label && findNode(label, node => ['input', 'textarea', 'select'].includes(node.type));
    const linked = label?.props.htmlFor && findNode(tree, node => node.props?.id === label.props.htmlFor);
    assert.ok(input ?? linked, 'Missing field ' + name);return input ?? linked;
  };
  flush();
  return {runtime, settle, flush, matching, button, field,
    click(name) {const target = button(name);assert.ok(target, 'Missing button ' + name);target.props.onClick();flush();},
    change(name, value) {const target = field(name);assert.ok(!target.props.disabled, name + ' is disabled');target.props.onChange({target: {value}});flush();},
    async respond(request, value, status = 200) {assert.ok(request, 'Missing HTTP request');request.resolve(json(value, status));await settle();},
    async openEditor(latest = record()) {
      await this.respond(matching('/posting-description')[0], envelope());
      this.click('Edit application');await this.respond(matching('')[0], latest);
      return matching('/posting-description')[1];
    },
    async submit() {
      const form = findNode(tree, node => node.type === 'form');assert.ok(form, 'Editor is not open');
      form.props.onSubmit({preventDefault() {}});await settle();
      return matching('', 'PUT').at(-1);
    },
  };
}

// Fault: substituting an empty description for a failed read would erase stored text on a metadata edit.
test('a failed editor description read permits metadata saving without sending descriptionSnapshot', async t => {
  const ui = drawer(t), read = await ui.openEditor();
  await ui.respond(read, {message: 'Description unavailable'}, 503);
  assert.equal(ui.field('Saved description').props.disabled, true);
  ui.change('Recruiter name', 'Updated recruiter');
  const write = await ui.submit();assert.ok(write, 'Metadata save should remain available');
  const payload = JSON.parse(write.options.body);
  assert.equal(payload.postingDetails.recruiterName, 'Updated recruiter');
  assert.equal(Object.hasOwn(payload.postingDetails, 'descriptionSnapshot'), false);
  assert.equal(write.options.headers.get('If-Match'), '7');
  await ui.respond(write, record({version: 8}));
});

// Fault: retrying through beginEditing would replace unsaved fields with another parent GET.
test('retrying a description read preserves other draft fields and the loaded text stays unchanged on save', async t => {
  const ui = drawer(t), read = await ui.openEditor();
  await ui.respond(read, {message: 'Description unavailable'}, 503);
  ui.change('Notes', 'Unsaved preparation');ui.change('Recruiter name', 'Draft recruiter');
  ui.change('Minimum compensation', '28.50');
  ui.click('Retry description');
  await ui.respond(ui.matching('/posting-description')[2], envelope(7, 'Recovered description'));
  assert.equal(ui.field('Saved description').props.disabled, false);
  assert.equal(ui.field('Saved description').props.value, 'Recovered description');
  assert.equal(ui.field('Notes').props.value, 'Unsaved preparation');
  assert.equal(ui.matching('').length, 1, 'Description retry must not replace the parent draft');
  const write = await ui.submit(), payload = JSON.parse(write.options.body);
  assert.equal(payload.notes, 'Unsaved preparation');
  assert.equal(payload.postingDetails.recruiterName, 'Draft recruiter');
  assert.equal(payload.postingDetails.compensationMin, '28.50');
  assert.equal(Object.hasOwn(payload.postingDetails, 'descriptionSnapshot'), false);
  await ui.respond(write, record({version: 8}));
});

// Fault: accepting the newest description without checking its parent version enables stale edits.
test('a description from another parent version remains disabled until the saved record is reloaded', async t => {
  const ui = drawer(t), read = await ui.openEditor();
  await ui.respond(read, envelope(8, 'Concurrent description'));
  assert.equal(ui.field('Saved description').props.disabled, true);
  assert.notEqual(ui.field('Saved description').props.value, 'Concurrent description');
  assert.ok(ui.button('Reload saved version (discard draft)'));
  ui.click('Reload saved version (discard draft)');
  await ui.respond(ui.matching('')[1], record({version: 8}));
  await ui.respond(ui.matching('/posting-description')[2], envelope(8, 'Concurrent description'));
  assert.equal(ui.field('Saved description').props.disabled, false);
  assert.equal(ui.field('Saved description').props.value, 'Concurrent description');
});

// Fault: reloading automatically after 409 discards edits; retaining version 7 after explicit reload repeats the conflict.
test('a 409 preserves the full draft until explicit reload replaces it and updates the next save version', async t => {
  const ui = drawer(t), read = await ui.openEditor();await ui.respond(read, envelope());
  ui.change('Notes', 'Keep my notes');ui.change('Recruiter name', 'Keep my recruiter');
  ui.change('Saved description', 'Keep my description');
  const first = await ui.submit();
  assert.equal(JSON.parse(first.options.body).postingDetails.descriptionSnapshot, 'Keep my description');
  await ui.respond(first, {message: 'Changed elsewhere'}, 409);
  assert.equal(ui.field('Notes').props.value, 'Keep my notes');
  assert.equal(ui.field('Recruiter name').props.value, 'Keep my recruiter');
  assert.equal(ui.field('Saved description').props.value, 'Keep my description');
  assert.equal(ui.matching('').length, 1, 'A conflict must not silently reload the draft');
  assert.equal(ui.matching('', 'PUT').length, 1, 'A conflict must not replay the write');
  ui.click('Reload saved version (discard draft)');
  await ui.respond(ui.matching('')[1], record({version: 8, notes: 'Latest saved notes'}));
  await ui.respond(ui.matching('/posting-description')[2], envelope(8, 'Latest saved description'));
  assert.equal(ui.field('Notes').props.value, 'Latest saved notes');
  assert.equal(ui.field('Recruiter name').props.value, 'Saved recruiter');
  assert.equal(ui.field('Saved description').props.value, 'Latest saved description');
  const next = await ui.submit();
  assert.equal(next.options.headers.get('If-Match'), '8');
  assert.equal(Object.hasOwn(JSON.parse(next.options.body).postingDetails, 'descriptionSnapshot'), false);
  await ui.respond(next, record({version: 9}));
});

// Fault: a closed drawer continues beginEditing after its parent GET and starts an unnecessary HTTP request.
test('unmounting while the editor parent read is pending prevents an obsolete description request', async t => {
  const ui = drawer(t);await ui.respond(ui.matching('/posting-description')[0], envelope());
  ui.click('Edit application');ui.runtime.unmount();
  ui.matching('')[0].resolve(json(record()));await tick();
  assert.equal(ui.matching('/posting-description').length, 1, 'Closing the drawer must end the editor read chain');
});
