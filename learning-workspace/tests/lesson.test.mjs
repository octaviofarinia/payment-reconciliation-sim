import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { purchases, settlement, references, outcomes, classify, evidenceFor, initialState, lessonReducer } from '../src/lesson.js';

const fixturePath = '../../acceptance-tests/src/test/resources/fixtures/';
const canonical = async (name) => JSON.parse(await readFile(new URL(`${fixturePath}${name}`, import.meta.url), 'utf8'));
const reduce = (...actions) => actions.reduce(lessonReducer, initialState);

test('the displayed Java comparator stays identical to the actual project source', async () => {
  const original = '../../reconciliation-worker/src/main/java/org/octavio/paymentreconciliationsim/worker/domain/ReconciliationComparator.java';
  const readSource = async (path) => (await readFile(new URL(path, import.meta.url), 'utf8')).replaceAll('\r\n', '\n');
  assert.equal(await readSource('../src/reference/ReconciliationComparator.java'), await readSource(original));
});

test('copied fixtures and derived evidence preserve the full canonical scenario', async () => {
  const expected = await canonical('canonical-expected.json');
  for (const name of ['canonical-purchases.json', 'canonical-settlement.json', 'canonical-expected.json']) {
    assert.deepEqual(JSON.parse(await readFile(new URL(`../src/data/${name}`, import.meta.url), 'utf8')), await canonical(name));
  }
  assert.deepEqual(purchases, (await canonical('canonical-purchases.json')).map(({ transactionReference, amountCentavos }) => ({ reference: transactionReference, amountCentavos })));
  assert.deepEqual(settlement, (await canonical('canonical-settlement.json')).map(({ transactionReference, amountCentavos, rowNumber }) => ({ reference: transactionReference, amountCentavos, rowNumber })));
  assert.equal(references.length, 100);
  assert.deepEqual(new Set(references), new Set(expected.results.map(({ reference }) => reference)));
  assert.deepEqual(new Set(outcomes.map(({ value }) => value)), new Set(Object.keys(expected.summary.outcomeCounts)));
  const counts = {};
  for (const result of expected.results) {
    const evidence = evidenceFor(result.reference);
    assert.equal(classify(result.reference), result.outcome);
    assert.equal(evidence.outcome, result.outcome);
    assert.equal(evidence.purchase?.amountCentavos ?? null, result.internalAmountCentavos);
    assert.deepEqual(evidence.rows, result.settlementEvidence);
    counts[evidence.outcome] = (counts[evidence.outcome] ?? 0) + 1;
  }
  assert.deepEqual(counts, expected.summary.outcomeCounts);
  assert.equal(purchases.length, expected.summary.internalPurchaseCount);
  assert.equal(settlement.length, expected.summary.settlementRowCount);
  assert.equal(new Set(settlement.map(({ reference }) => reference)).size, expected.summary.distinctSettlementReferenceCount);
  assert.equal(references.length, expected.summary.totalResultCount);
});

test('duplicate rows remain distinct and take precedence over equal amounts', () => {
  const evidence = evidenceFor('DUP-001');
  assert.equal(evidence.outcome, 'DUPLICATE');
  assert.deepEqual(evidence.rows.map(({ rowNumber }) => rowNumber), [4, 5]);
  assert.ok(evidence.rows.every(({ amountCentavos }) => amountCentavos === evidence.purchase.amountCentavos));
});

test('missing counterparts are represented by absent evidence', () => {
  assert.deepEqual(evidenceFor('MISS-001'), { purchase: { reference: 'MISS-001', amountCentavos: 20000 }, rows: [], outcome: 'MISSING_IN_SETTLEMENT' });
  assert.deepEqual(evidenceFor('EXT-001'), { purchase: null, rows: [{ reference: 'EXT-001', amountCentavos: 30000, rowNumber: 2 }], outcome: 'MISSING_INTERNALLY' });
});

test('canonical records are immutable across lesson interactions', () => {
  assert.throws(() => { purchases[0].amountCentavos = 0; }, TypeError);
  assert.throws(() => { settlement.push({}); }, TypeError);
});

test('all three modes retain selection, predictions, and assessed progress', () => {
  let state = reduce({ type: 'predict', value: 'AMOUNT_MISMATCH' }, { type: 'check' });
  for (const mode of ['Course', 'Missions', 'Explorer']) {
    state = lessonReducer(state, { type: 'mode', mode });
    assert.equal(state.mode, mode);
    assert.equal(state.selected, 'AMOUNT-001');
    assert.deepEqual(state.predictions, { 'AMOUNT-001': 'AMOUNT_MISMATCH' });
    assert.deepEqual(state.completed, { 'AMOUNT-001': true });
  }
});

test('record changes clear feedback while retaining independent predictions', () => {
  const state = reduce({ type: 'predict', value: 'AMOUNT_MISMATCH' }, { type: 'check' }, { type: 'select', reference: 'MISS-001' }, { type: 'predict', value: 'MISSING_IN_SETTLEMENT' }, { type: 'select', reference: 'AMOUNT-001' });
  assert.equal(state.feedback, null);
  assert.deepEqual(state.predictions, { 'AMOUNT-001': 'AMOUNT_MISMATCH', 'MISS-001': 'MISSING_IN_SETTLEMENT' });
  assert.deepEqual(state.completed, { 'AMOUNT-001': true });
});

test('wrong answers never advance progress and repeated answers never count twice', () => {
  let state = reduce({ type: 'predict', value: 'MATCHED' }, { type: 'check' });
  assert.deepEqual(state.completed, {});
  assert.deepEqual(state.feedback, { correct: false, reference: 'AMOUNT-001', expected: 'AMOUNT_MISMATCH' });
  state = lessonReducer(state, { type: 'predict', value: 'AMOUNT_MISMATCH' });
  state = lessonReducer(state, { type: 'check' });
  assert.deepEqual(state.feedback, { correct: true, reference: 'AMOUNT-001', expected: 'AMOUNT_MISMATCH' });
  state = lessonReducer(state, { type: 'check' });
  assert.equal(Object.keys(state.completed).length, 1);
  state = lessonReducer(state, { type: 'predict', value: 'DUPLICATE' });
  state = lessonReducer(state, { type: 'check' });
  assert.deepEqual(state.completed, { 'AMOUNT-001': true });
});

test('next cycles to uncompleted references and preserves selection when finished', () => {
  let state = reduce({ type: 'select', reference: references.at(-1) }, { type: 'next' });
  assert.equal(state.selected, references[0]);
  for (const reference of references) {
    state = lessonReducer(state, { type: 'select', reference });
    state = lessonReducer(state, { type: 'predict', value: classify(reference) });
    state = lessonReducer(state, { type: 'check' });
  }
  assert.equal(Object.keys(state.completed).length, 100);
  assert.equal(lessonReducer(state, { type: 'next' }).selected, references.at(-1));
  const partial = reduce({ type: 'predict', value: 'AMOUNT_MISMATCH' }, { type: 'check' }, { type: 'select', reference: 'MISS-001' }, { type: 'next' });
  assert.equal(partial.selected, references[3]);
  assert.equal(partial.feedback, null);
});

test('course steps preserve the selected case and reset clears all session state', () => {
  const state = reduce({ type: 'mode', mode: 'Course' }, { type: 'step', step: 2 }, { type: 'predict', value: 'AMOUNT_MISMATCH' }, { type: 'check' });
  assert.equal(state.step, 2);
  assert.equal(state.selected, 'AMOUNT-001');
  assert.deepEqual(lessonReducer(state, { type: 'reset' }), { mode: 'Explorer', selected: 'AMOUNT-001', predictions: {}, completed: {}, feedback: null, step: 0 });
  assert.deepEqual(initialState, { mode: 'Explorer', selected: 'AMOUNT-001', predictions: {}, completed: {}, feedback: null, step: 0 });
});
