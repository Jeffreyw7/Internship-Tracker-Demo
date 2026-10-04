// Exercise real cache mutations and HTTP adapters with reads that finish in controlled order.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import {applicationApi} from '../src/data/applicationApi.ts';
import {ApiError} from '../src/data/api.ts';
import {blankDraft} from '../src/domain/application.ts';
import {createReactHost} from './helpers/reactHost.mjs';

const compiled = ts.transpileModule(await readFile(new URL('../src/hooks/useApplications.ts', import.meta.url), 'utf8'), {
  compilerOptions: {module: ts.ModuleKind.CommonJS},
}).outputText;
const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: {'Content-Type': 'application/json'},
});
const record = (version = 1, deleted = false) => ({...blankDraft(), id: 'A', company: 'Example', role: 'Intern',
  notes: version === 1 ? 'Old notes' : 'Saved notes', version, deleted, sample: false,
  createdAt: '2026-10-02T12:00:00Z', updatedAt: '2026-10-03T12:00:00Z'});
const page = content => ({content, totalElements: content.length, totalPages: 1, page: 0, size: 100});

async function workspace(t) {
  const runtime = createReactHost(), requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (url, options) => url === '/api/auth/csrf'
    ? Promise.resolve(json({token: 'test-csrf', headerName: 'X-CSRF-TOKEN'}))
    : new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
  t.after(() => {runtime.unmount();globalThis.fetch = originalFetch;});
  const module = {exports: {}};
  const modules = {react: runtime.hooks, '../data/applicationApi': {applicationApi}, '../data/api': {ApiError}};
  new Function('require', 'module', 'exports', compiled)(name => {
    assert.ok(name in modules, 'Unmapped import ' + name);return modules[name];
  }, module, module.exports);
  let value;
  function flush() {
    for (let i = 0; i < 20; i++) {
      value = runtime.render(() => module.exports.useApplications());runtime.commit();
      if (!runtime.dirty) return;
    }
    assert.fail('Application hook did not settle');
  }
  const settle = async () => {await new Promise(resolve => setImmediate(resolve));flush();};
  flush();requests[0].resolve(json(page([record()])));await settle();
  return {requests, settle, flush, get value() {return value;},
    reads: () => requests.filter(request => request.url.includes('/applications?')),
    writes: () => requests.filter(request => request.options.method && request.options.method !== 'GET'),
  };
}

for (const operation of ['save', 'remove', 'restoreDeleted']) {
  // Fault: a read started during the write can replace the confirmed mutation after it completes.
  test(`an overlapping refresh cannot undo a confirmed ${operation} in the application cache`, async t => {
    const ui = await workspace(t);
    const mutation = operation === 'save' ? ui.value.save(record(), 'A', 1) : ui.value[operation](record());
    await ui.settle();
    const refresh = ui.value.refresh();
    const saved = record(2, operation === 'remove');
    ui.writes()[0].resolve(json(saved));await mutation;ui.flush();
    const expected = operation === 'remove' ? [] : [saved];
    assert.deepEqual(ui.value.apps, expected);
    ui.reads()[1].resolve(json(page([record()])));await refresh;ui.flush();
    assert.deepEqual(ui.value.apps, expected);
    assert.equal(ui.value.ready, true);assert.equal(ui.value.error, '');
  });
}

test('a refresh failure from during a successful save cannot mark the saved workspace unavailable', async t => {
  const ui = await workspace(t), saving = ui.value.save(record(), 'A', 1);await ui.settle();
  const refresh = ui.value.refresh();
  ui.writes()[0].resolve(json(record(2)));await saving;ui.flush();
  ui.reads()[1].reject(new Error('Obsolete read failure'));await refresh;ui.flush();
  assert.deepEqual(ui.value.apps, [record(2)]);
  assert.equal(ui.value.ready, true);assert.equal(ui.value.error, '');
});

test('a confirmed save keeps the loaded cache usable after an overlapping refresh already failed', async t => {
  const ui = await workspace(t), saving = ui.value.save(record(), 'A', 1);await ui.settle();
  const refresh = ui.value.refresh();
  ui.reads()[1].reject(new Error('Read failed while saving'));await refresh;ui.flush();
  ui.writes()[0].resolve(json(record(2)));await saving;ui.flush();
  assert.deepEqual(ui.value.apps, [record(2)]);
  assert.equal(ui.value.ready, true);assert.equal(ui.value.error, '');
});

test('a conflict still refreshes saved records and surfaces the original 409 without replaying the write', async t => {
  const ui = await workspace(t);
  const outcome = ui.value.save(record(), 'A', 1).catch(error => error);await ui.settle();
  ui.writes()[0].resolve(json({message: 'Changed elsewhere'}, 409));await ui.settle();
  assert.equal(ui.reads().length, 2);
  ui.reads()[1].resolve(json(page([record(2)])));
  const error = await outcome;ui.flush();
  assert.equal(error.status, 409);assert.deepEqual(ui.value.apps, [record(2)]);
  assert.equal(ui.value.ready, true);assert.equal(ui.writes().length, 1);
});

test('a refresh started after a save still accepts newer server records', async t => {
  const ui = await workspace(t), saving = ui.value.save(record(), 'A', 1);await ui.settle();
  ui.writes()[0].resolve(json(record(2)));await saving;ui.flush();
  const refresh = ui.value.refresh();
  ui.reads()[1].resolve(json(page([record(3)])));await refresh;ui.flush();
  assert.deepEqual(ui.value.apps, [record(3)]);assert.equal(ui.value.ready, true);
});
