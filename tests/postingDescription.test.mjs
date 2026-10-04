// Run the real hooks and drawer/workspace handlers; only API promises and the React host are controlled.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import * as application from '../src/domain/application.ts';
import * as postingDetails from '../src/domain/postingDetails.ts';
import * as selectors from '../src/domain/selectors.ts';
import * as applicationExport from '../src/domain/applicationExport.ts';
import {ApiError} from '../src/data/api.ts';
import {createReactHost as host, findNode as find} from './helpers/reactHost.mjs';

const compiled = Object.fromEntries(await Promise.all([
  'hooks/usePostingDescription.ts', 'hooks/useApplications.ts', 'components/ApplicationDrawer.tsx', 'App.tsx',
].map(async path => [path, ts.transpileModule(await readFile(new URL('../src/' + path, import.meta.url), 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX},
}).outputText])));

function load(path, modules) {
  const module = {exports: {}};
  new Function('require', 'module', 'exports', 'window', compiled[path])(name => {
    assert.ok(name in modules, 'Unmapped import ' + name);return modules[name];
  }, module, module.exports, {addEventListener() {}, removeEventListener() {}});
  return module.exports;
}

function endpoint() {
  const requests = [];
  return {requests, call: (...args) => new Promise((resolve, reject) => requests.push({args, resolve, reject}))};
}
const tick = async () => {for (let i = 0; i < 6; i++) await Promise.resolve();};
const envelope = (applicationVersion, descriptionText = 'Saved description') => ({applicationVersion,
  snapshot: {id: 'snapshot-' + applicationVersion, revision: applicationVersion, sourceUrl: 'https://example.test/job',
    capturedAt: '2026-10-03T12:00:00Z', captureMethod: 'MANUAL', descriptionText}});
const record = (version = 1) => ({id: 'A', company: 'Example', role: 'Intern', location: '', status: 'Saved',
  appliedDate: '', deadline: '', followUpDate: '', notes: '', stages: [],
  createdAt: '2026-10-02T08:00:00Z', updatedAt: '2026-10-03T12:00:00Z', version, sample: false, deleted: false,
  postingDetails: {...postingDetails.blankPostingDetails(), recruiterName: version === 1 ? 'Old recruiter' : 'New recruiter',
    compensationMin: version === 1 ? '20.00' : '30.00', compensationCurrency: 'USD', compensationPeriod: 'HOUR',
    compensationStatus: 'PAID', descriptionRevision: version}});

function descriptionHook() {
  const runtime = host(), current = endpoint(), refresh = endpoint();
  const {usePostingDescription} = load('hooks/usePostingDescription.ts', {react: runtime.hooks, '../data/postingApi': {postingApi: {current: current.call}}});
  let args = ['A', 1, refresh.call], result;
  const render = () => {result = runtime.render(() => usePostingDescription(...args));return result;};
  const flush = () => {
    for (let i = 0; i < 20; i++) {render();runtime.commit();if (!runtime.dirty) return result;}
    assert.fail('Hook did not settle');
  };
  flush();
  return {current, refresh, runtime, flush, render, setApplication(id, version) {args = [id, version, refresh.call];},
    async settle() {await tick();return flush();}};
}

function workspace() {
  const appHost = host(), drawerHost = host(), list = endpoint(), current = endpoint(), get = endpoint(), save = endpoint();
  const jsx = (type, props, key) => ({type, props, key});
  const shared = {'react/jsx-runtime': {jsx, jsxs: jsx}, 'lucide-react': new Proxy({}, {get: (_, name) => String(name)}),
    '../data/api': {ApiError}, '../data/applicationApi': {applicationApi: {list: list.call, get: get.call, save: save.call}},
    '../data/postingApi': {postingApi: {current: current.call}}};
  const {useApplications} = load('hooks/useApplications.ts', {...shared, react: appHost.hooks});
  const {usePostingDescription} = load('hooks/usePostingDescription.ts', {...shared, react: drawerHost.hooks});
  const {ApplicationDrawer} = load('components/ApplicationDrawer.tsx', {...shared, react: drawerHost.hooks,
    '../domain/application': application, '../domain/postingDetails': postingDetails,
    '../hooks/usePostingDescription': {usePostingDescription}, './Shared': {CompanyMark: 'CompanyMark', StatusBadge: 'StatusBadge'},
    './PostingFields': {PostingFields: 'PostingFields'}, './PostingDetailsPanel': {PostingDetailsPanel: 'PostingDetailsPanel'}});
  const {default: App} = load('App.tsx', {...shared, react: appHost.hooks, './hooks/useApplications': {useApplications},
    './domain/application': application, './domain/selectors': selectors, './domain/applicationExport': applicationExport,
    './components/ApplicationDrawer': {ApplicationDrawer}, './components/ReminderCenter': {default: 'ReminderCenter'},
    './components/Shared': {CompanyButton: 'CompanyButton', CompanyMark: 'CompanyMark', StatusBadge: 'StatusBadge'}});
  let tree, drawer;
  const flush = () => {
    for (let i = 0; i < 20; i++) {
      tree = appHost.render(() => App({user: {id: 'owner', email: 'owner@example.test', displayName: 'Test'}, onLogout: async () => {}, onSecurity() {}}));
      appHost.commit();
      const node = find(tree, n => n.type === ApplicationDrawer);
      drawer = node ? drawerHost.render(() => ApplicationDrawer(node.props)) : undefined;
      if (node) drawerHost.commit();
      if (!appHost.dirty && !drawerHost.dirty) return;
    }
    assert.fail('Workspace did not settle');
  };
  flush();
  return {list, current, get, save, flush, async settle() {await tick();flush();},
    open() {find(tree, n => n.props?.['aria-label'] === 'View Example details').props.onClick();flush();},
    panel() {return find(drawer, n => n.type === 'PostingDetailsPanel').props;},
    field(predicate) {return find(drawer, predicate);},
    refresh() {find(tree, n => n.type === 'button' && n.props?.children === 'Refresh').props.onClick();flush();},
    edit() {const button = find(drawer, n => n.type === 'button' && find(n.props.children, c => c.type === 'Pencil'));return button.props.onClick();}};
}

test('a description from a newer parent version stays hidden and requests a shared refresh', async () => {
  const ui = descriptionHook();
  ui.current.requests[0].resolve(envelope(2, 'New description'));
  const result = await ui.settle();
  assert.equal(result.current, null);
  assert.equal(result.loading, false);
  assert.match(result.error, /changed|refresh/i);
  assert.equal(ui.refresh.requests.length, 1);
});

test('a mismatched null snapshot is an error rather than an empty saved description', async () => {
  const ui = descriptionHook();
  ui.current.requests[0].resolve({applicationVersion: 2, snapshot: null});
  const result = await ui.settle();
  assert.equal(result.current, null);
  assert.ok(result.error);
});

test('refresh replaces shared contacts and pay before the matching description becomes visible', async () => {
  const ui = workspace();ui.list.requests[0].resolve([record()]);await ui.settle();ui.open();
  ui.current.requests[0].resolve(envelope(2, 'New description'));await ui.settle();
  assert.equal(ui.panel().current, null);
  assert.ok(ui.panel().error);
  assert.equal(ui.list.requests.length, 2);
  ui.list.requests[1].resolve([record(2)]);await ui.settle();
  assert.equal(ui.panel().value.recruiterName, 'New recruiter');
  assert.equal(ui.panel().value.compensationMin, '30.00');
  assert.equal(ui.panel().current, null);
  ui.current.requests[1].resolve(envelope(2, 'New description'));await ui.settle();
  assert.equal(ui.panel().current.snapshot.descriptionText, 'New description');
  assert.equal(ui.panel().error, '');
});

test('retry recovers when refreshing shared application data initially fails', async () => {
  const ui = workspace();ui.list.requests[0].resolve([record()]);await ui.settle();ui.open();
  ui.current.requests[0].resolve(envelope(2));await ui.settle();
  assert.equal(ui.panel().current, null);
  assert.equal(ui.list.requests.length, 2);
  ui.list.requests[1].reject(new Error('Connection unavailable'));await ui.settle();
  ui.panel().onRetry();ui.flush();
  ui.current.requests[1].resolve(envelope(2));await ui.settle();
  assert.equal(ui.panel().current, null);
  assert.equal(ui.list.requests.length, 3);
  ui.list.requests[2].resolve([record(2)]);await ui.settle();
  ui.current.requests[2].resolve(envelope(2, 'Recovered description'));await ui.settle();
  assert.equal(ui.panel().current.snapshot.descriptionText, 'Recovered description');
  assert.equal(ui.panel().value.recruiterName, 'New recruiter');
});

test('description read failures stay retryable and a matching empty snapshot is accepted', async () => {
  const ui = descriptionHook();ui.current.requests[0].reject(new Error('Read unavailable'));
  let result = await ui.settle();
  assert.equal(result.current, null);assert.equal(result.error, 'Read unavailable');
  result.retry();ui.flush();ui.current.requests[1].resolve({applicationVersion: 1, snapshot: null});
  result = await ui.settle();
  assert.deepEqual(result.current, {applicationVersion: 1, snapshot: null});assert.equal(result.error, '');
  assert.equal(ui.refresh.requests.length, 0);
});

test('a previously accepted description is hidden immediately when the displayed version changes', async () => {
  const ui = descriptionHook();ui.current.requests[0].resolve(envelope(1, 'Previous description'));await ui.settle();
  ui.setApplication('A', 2);
  // Assert the first render, before effects can reset the old request state.
  assert.equal(ui.render().current, null);
  ui.flush();ui.current.requests[1].resolve(envelope(2, 'Updated description'));
  assert.equal((await ui.settle()).current.snapshot.descriptionText, 'Updated description');
});

test('switching applications immediately hides the old description even when versions match', async () => {
  const ui = descriptionHook();ui.current.requests[0].resolve(envelope(1, 'Application A'));await ui.settle();
  ui.setApplication('B', 1);assert.equal(ui.render().current, null);
  ui.flush();ui.current.requests[1].resolve(envelope(1, 'Application B'));
  assert.equal((await ui.settle()).current.snapshot.descriptionText, 'Application B');
});

test('obsolete application responses cannot replace current text or trigger a refresh', async () => {
  const ui = descriptionHook();ui.setApplication('B', 1);ui.flush();
  ui.current.requests[1].resolve(envelope(1, 'Application B'));await ui.settle();
  ui.current.requests[0].resolve(envelope(2, 'Obsolete application A'));
  assert.equal((await ui.settle()).current.snapshot.descriptionText, 'Application B');
  assert.equal(ui.refresh.requests.length, 0);
  assert.equal(ui.current.requests[0].args[1].aborted, true);
});

test('an old version read cannot replace a newer read that completed first', async () => {
  const ui = descriptionHook();ui.setApplication('A', 2);ui.flush();
  ui.current.requests[1].resolve(envelope(2, 'Current version'));await ui.settle();
  ui.current.requests[0].reject(new Error('Obsolete failure'));
  const result = await ui.settle();
  assert.equal(result.current.snapshot.descriptionText, 'Current version');assert.equal(result.error, '');
});

test('closing the drawer prevents a pending response from updating state or refreshing applications', async () => {
  const ui = descriptionHook();ui.runtime.unmount();
  ui.current.requests[0].resolve(envelope(2));await tick();
  assert.equal(ui.runtime.lateWrites, 0);
  assert.equal(ui.refresh.requests.length, 0);
  assert.equal(ui.current.requests[0].args[1].aborted, true);
});

test('a shared refresh preserves an active editable draft and its original save version', async () => {
  const ui = workspace();ui.list.requests[0].resolve([record()]);await ui.settle();ui.open();
  ui.current.requests[0].resolve(envelope(1));await ui.settle();
  ui.edit();ui.get.requests[0].resolve(record());await ui.settle();
  ui.current.requests[1].resolve(envelope(1, 'Editing baseline'));await ui.settle();
  ui.field(n => n.type === 'textarea' && n.props.id === 'notes').props.onChange({target: {value: 'Unsaved notes'}});ui.flush();
  ui.field(n => n.type === 'PostingFields').props.onChange({...record().postingDetails, descriptionSnapshot: 'Unsaved description'});ui.flush();
  ui.refresh();ui.list.requests[1].resolve([record(2)]);await ui.settle();
  ui.current.requests[2].resolve(envelope(2, 'Another editor description'));await ui.settle();
  assert.equal(ui.field(n => n.type === 'textarea' && n.props.id === 'notes').props.value, 'Unsaved notes');
  assert.equal(ui.field(n => n.type === 'PostingFields').props.descriptionValue, 'Unsaved description');
  ui.field(n => n.type === 'form').props.onSubmit({preventDefault() {}});
  const [draft, id, version] = ui.save.requests[0].args;
  assert.equal(id, 'A');assert.equal(version, 1);
  assert.equal(draft.notes, 'Unsaved notes');assert.equal(draft.postingDetails.descriptionSnapshot, 'Unsaved description');
});
