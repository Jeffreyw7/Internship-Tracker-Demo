// Exercise App's real event handlers with controlled refresh responses and persistent hook state.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import * as application from '../src/domain/application.ts';
import * as selectors from '../src/domain/selectors.ts';

const source = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText;

function workspace() {
  const state = [], refs = [], requests = [];
  let stateCursor = 0, refCursor = 0;
  const apps = ['A', 'B'].map(id => ({ id, company: id, role: 'Intern', location: '', status: 'Saved',
    appliedDate: '', deadline: '', followUpDate: '', notes: '', stages: [],
    createdAt: '2026-10-02T08:00:00Z', updatedAt: '2026-10-02T08:00:00Z', version: 0, sample: false, deleted: false }));
  const hooks = {
    useState(initial) {
      const index = stateCursor++;
      if (!(index in state)) state[index] = typeof initial === 'function' ? initial() : initial;
      return [state[index], value => { state[index] = typeof value === 'function' ? value(state[index]) : value; }];
    },
    useRef(initial) { const index = refCursor++;return refs[index] ??= { current: initial }; },
    useEffect() {}, useMemo(fn) { return fn(); },
  };
  const jsx = (type, props, key) => ({ type, props, key });
  const modules = {
    'react': hooks, 'react/jsx-runtime': { jsx, jsxs: jsx },
    'lucide-react': new Proxy({}, { get: (_, name) => String(name) }),
    './domain/application': application, './domain/selectors': selectors,
    './hooks/useApplications': { useApplications: () => ({ apps, error: '', ready: true, busy: false,
      refresh: () => new Promise(resolve => { requests.push(resolve); }) }) },
    './components/ApplicationDrawer': { ApplicationDrawer: 'ApplicationDrawer' },
    './components/ReminderCenter': { default: 'ReminderCenter' },
    './components/Shared': { CompanyButton: 'CompanyButton', CompanyMark: 'CompanyMark', StatusBadge: 'StatusBadge' },
  };
  const module = { exports: {} };
  new Function('require', 'module', 'exports', compiled)(name => {
    assert.ok(name in modules, 'Unmapped import ' + name);return modules[name];
  }, module, module.exports);
  const render = () => {
    stateCursor = 0;refCursor = 0;
    return module.exports.default({ user: { id: 'owner', email: 'owner@example.test', displayName: 'Navigation Test' },
      onLogout: async () => {}, onSecurity: () => {} });
  };
  const launch = () => { find(render(), n => n.props?.className === 'text-button reminder-launcher').props.onClick(); };
  const center = () => find(render(), n => n.type === 'ReminderCenter');
  const drawer = () => find(render(), n => n.type === 'ApplicationDrawer');
  const finish = async index => { requests[index]();await Promise.resolve();await Promise.resolve(); };
  return { render, launch, center, drawer, finish };
}

function find(node, predicate) {
  if (Array.isArray(node)) {
    for (const child of node) { const result = find(child, predicate);if (result) return result; }
  } else if (node && typeof node === 'object') {
    if (predicate(node)) return node;
    return find(node.props?.children, predicate);
  }
}

test('closing reminders cancels a delayed application link before it replaces a newer drawer', async () => {
  const ui = workspace();ui.launch();
  ui.center().props.onOpenApplication('A');ui.center().props.onClose();
  find(ui.render(), n => n.props?.['aria-label'] === 'View B details').props.onClick();
  assert.equal(ui.drawer().props.application.id, 'B');
  await ui.finish(0);
  assert.equal(ui.drawer().props.application.id, 'B');
});

test('closing and reopening reminders prevents an older link from closing the new center', async () => {
  const ui = workspace();ui.launch();
  ui.center().props.onOpenApplication('A');ui.center().props.onClose();ui.launch();
  await ui.finish(0);
  assert.ok(ui.center());assert.equal(ui.drawer(), undefined);
});

test('the newest reminder link wins when refresh responses finish in reverse order', async () => {
  const ui = workspace();ui.launch();
  ui.center().props.onOpenApplication('A');ui.center().props.onOpenApplication('B');
  await ui.finish(1);assert.equal(ui.drawer().props.application.id, 'B');
  await ui.finish(0);assert.equal(ui.drawer().props.application.id, 'B');
});

test('a completed reminder link opens its application', async () => {
  const ui = workspace();ui.launch();ui.center().props.onOpenApplication('A');
  await ui.finish(0);
  assert.equal(ui.center(), undefined);assert.equal(ui.drawer().props.application.id, 'A');
});
