import test from 'node:test';
import assert from 'node:assert/strict';
import { createProgress, progressReducer, serializeProgress, restoreProgress, courseStats, moduleComplete } from '../src/progress.js';
import { parseRoute, routeFor } from '../src/course-model.js';
import { references, classify } from '../src/lesson.js';

test('opening every lesson does not award assessed mission completion', () => {
  let state = createProgress();
  for (const moduleId of ['reconciliation', 'api', 'stability', 'upload', 'worker', 'security', 'recovery', 'capstone']) {
    for (let step = 0; step < 3; step++) state = progressReducer(state, { type: 'navigate', overview: false, mode: 'Course', moduleId, step });
  }
  assert.equal(courseStats(state).opened, 24);
  assert.equal(courseStats(state).count, 0);
});
test('cross-module navigation and persistence retain the canonical selected record and predictions', () => {
  let state = progressReducer(createProgress(), { type: 'select', reference: 'DUP-001' });
  state = progressReducer(state, { type: 'predict', value: 'DUPLICATE' });
  state = progressReducer(state, { type: 'check' });
  state = progressReducer(state, { type: 'navigate', moduleId: 'recovery', mode: 'Explorer', lens: 'system', overview: false });
  const restored = restoreProgress(serializeProgress(state));
  assert.equal(restored.selected, 'DUP-001');
  assert.equal(restored.predictions['DUP-001'], 'DUPLICATE');
  assert.equal(restored.completed['DUP-001'], true);
  assert.equal(restored.moduleId, 'recovery');
  assert.equal(restored.lens, 'system');
});
test('all five correct classifications earn one module; reset is scoped', () => {
  let state = createProgress();
  state = progressReducer(state, { type: 'navigate', moduleId: 'api', step: 1, overview: false });
  state = progressReducer(state, { type: 'bookmark' });
  for (const reference of references) {
    state = progressReducer(state, { type: 'select', reference });
    state = progressReducer(state, { type: 'predict', value: classify(reference) });
    state = progressReducer(state, { type: 'check' });
  }
  assert.equal(moduleComplete(state, 'reconciliation'), true);
  assert.equal(courseStats(state).count, 1);
  state = progressReducer(state, { type: 'reset-module', moduleId: 'reconciliation' });
  assert.equal(courseStats(state).count, 0);
  assert.deepEqual(state.bookmarks, ['api:1']);
  assert.equal(state.moduleId, 'api');
});
test('invalid storage is rejected, unknown references and lesson keys are sanitized', () => {
  assert.throws(() => restoreProgress('{'), /valid JSON/);
  assert.throws(() => restoreProgress('{"version":1}'), /unsupported/);
  const saved = JSON.parse(serializeProgress(createProgress()));
  saved.selected = 'FAKE'; saved.visited = ['api:1', 'api:90', 'other:0']; saved.bookmarks = ['api:1', 'api:1'];
  saved.labEvents.api = { arbitrary: true };
  assert.throws(() => restoreProgress(JSON.stringify(saved)), /invalid lab history/);
  saved.labEvents.api = [];
  const restored = restoreProgress(JSON.stringify(saved));
  assert.equal(restored.selected, 'AMOUNT-001');
  assert.deepEqual(restored.visited, ['api:1']);
  assert.deepEqual(restored.bookmarks, ['api:1']);
});
test('lesson, explorer and mission routes roundtrip and reject unknown modules', () => {
  for (const hash of ['#/course/api/2', '#/explorer/upload/system', '#/missions/recovery', '#/overview']) {
    const state = { ...createProgress(), ...parseRoute(hash) };
    assert.equal(routeFor(state), hash);
  }
  assert.equal(parseRoute('#/missions/missing'), null);
  assert.equal(parseRoute('#/course/api/900').step, 2);
});
test('a maximum-size CSV edit with JSON escaping survives persistence', () => {
  const csv = '"\\\n'.repeat(4000);
  let state = createProgress();
  state = progressReducer(state, { type: 'lab', moduleId: 'worker', action: { type: 'configure', key: 'csv', value: csv } });
  assert.equal(restoreProgress(serializeProgress(state)).labs.worker.fields.csv, csv);
});
test('ordinary textarea editing is coalesced while assessed commands retain their inputs', () => {
  let state = createProgress();
  for (let index = 0; index < 1100; index++) state = progressReducer(state, { type: 'lab', moduleId: 'worker', action: { type: 'configure', key: 'csv', value: 'a'.repeat(4000) + index } });
  assert.equal(state.labEvents.worker.length, 1);
  assert.ok(serializeProgress(state).length < 10000);
  state = progressReducer(state, { type: 'lab', moduleId: 'worker', action: { type: 'validate' } });
  state = progressReducer(state, { type: 'lab', moduleId: 'worker', action: { type: 'configure', key: 'csv', value: 'new edit' } });
  assert.equal(state.labEvents.worker.length, 3);
  const restored = restoreProgress(serializeProgress(state));
  assert.deepEqual(restored.labs.worker, state.labs.worker);
});
