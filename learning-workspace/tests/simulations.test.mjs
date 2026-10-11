import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createLab, transitionLab, isLabComplete, canonicalCsv, canonicalMetadata } from '../src/simulations.js';

const step = (id, state, type, extra = {}) => transitionLab(id, state, { type, ...extra });
const field = (id, state, key, value) => step(id, state, 'configure', { key, value });

test('the displayed registration metadata describes the exact canonical CSV bytes', () => {
  assert.deepEqual(Object.keys(canonicalMetadata).sort(), ['businessDate', 'byteLength', 'sha256']);
  assert.equal(Buffer.byteLength(canonicalCsv), canonicalMetadata.byteLength);
  assert.equal(createHash('sha256').update(canonicalCsv).digest('hex'), canonicalMetadata.sha256);
});

test('API lab requires the public workflow and a rejected cross-role request', () => {
  let s = createLab('api');
  s = field('api', s, 'endpoint', 'run'); s = step('api', s, 'send');
  assert.equal(s.machine.run, false);
  s = field('api', s, 'endpoint', 'date'); s = step('api', s, 'send');
  s = field('api', s, 'endpoint', 'purchase'); s = step('api', s, 'send');
  s = field('api', s, 'endpoint', 'close'); s = step('api', s, 'send');
  s = field('api', s, 'endpoint', 'run'); s = step('api', s, 'send');
  assert.equal(isLabComplete('api', s), false);
  s = field('api', s, 'role', 'worker'); s = step('api', s, 'send');
  assert.equal(s.machine.response, 403);
  assert.equal(isLabComplete('api', s), true);
});

test('stable inputs replay unchanged, reject conflicts and reject new closed-date writes', () => {
  let s = createLab('stability');
  s = step('stability', s, 'write'); s = step('stability', s, 'write');
  s = field('stability', s, 'amount', '11000'); s = step('stability', s, 'write');
  assert.equal(s.machine.amount, 10000);
  s = step('stability', s, 'close');
  s = field('stability', s, 'reference', 'NEW-001'); s = step('stability', s, 'write');
  assert.equal(isLabComplete('stability', s), true);
  assert.equal(s.machine.count, 1);
});

test('upload rejects invalid transport and never replaces the bound original', () => {
  let s = createLab('upload');
  s = step('upload', s, 'register');
  s = field('upload', s, 'expiry', 'expired'); s = step('upload', s, 'upload');
  assert.equal(s.machine.versions, 0);
  s = field('upload', s, 'expiry', 'valid'); s = step('upload', s, 'upload');
  s = step('upload', s, 'bind');
  s = step('upload', s, 'upload'); s = step('upload', s, 'bind');
  assert.equal(s.machine.original, 'v1');
  assert.equal(s.machine.latest, 'v2');
  assert.equal(isLabComplete('upload', s), true);
});

test('CSV rejection publishes no partial output; repair computes all canonical results', () => {
  let s = createLab('worker');
  s = step('worker', s, 'validate');
  assert.equal(s.machine.validated, false);
  s = step('worker', s, 'compare'); s = step('worker', s, 'publish');
  assert.equal(s.machine.published, false);
  s = field('worker', s, 'csv', canonicalCsv); s = step('worker', s, 'validate');
  s = step('worker', s, 'compare'); s = step('worker', s, 'publish');
  assert.equal(s.machine.results.length, 5);
  assert.equal(s.machine.results.find(r => r.reference === 'DUP-001').outcome, 'DUPLICATE');
  assert.equal(isLabComplete('worker', s), true);
  s = field('worker', s, 'csv', canonicalCsv.replace('45000', '-45000'));
  assert.equal(s.machine.validated, false);
  assert.equal(s.machine.results.length, 0);
  assert.equal(isLabComplete('worker', s), true, 'earned completion survives later exploration');
});

test('CSV parser rejects missing fields and wrong date before comparison', () => {
  for (const csv of [canonicalCsv.replace(',45000,ARS', ',45000'), canonicalCsv.replace('2026-10-01', '2026-10-02')]) {
    let s = field('worker', createLab('worker'), 'csv', csv);
    s = step('worker', s, 'validate');
    assert.equal(s.machine.validated, false);
    assert.equal(s.machine.results.length, 0);
  }
});

test('network gates distinguish API token from S3 IAM and retain intended boundaries', () => {
  let s = createLab('security');
  s = field('security', s, 'route', 'blocked'); s = step('security', s, 'probe');
  assert.equal(s.machine.allowed, false);
  s = field('security', s, 'route', 'present'); s = step('security', s, 'probe');
  assert.equal(s.machine.allowed, true);
  s = field('security', s, 'destination', 's3');
  s = field('security', s, 'iam', 'denied'); s = step('security', s, 'probe');
  assert.equal(s.machine.allowed, false);
  s = field('security', s, 'iam', 'allowed'); s = step('security', s, 'probe');
  s = field('security', s, 'destination', 'atlas'); s = step('security', s, 'probe');
  assert.equal(s.machine.allowed, false);
  s = field('security', s, 'source', 'public'); s = field('security', s, 'destination', 'api'); s = step('security', s, 'probe');
  assert.equal(isLabComplete('security', s), true);
});

test('recovery requires a real failed attempt, original input and unchanged completed replay', () => {
  let s = createLab('recovery');
  s = step('recovery', s, 'inject'); s = step('recovery', s, 'attempt');
  assert.equal(s.machine.status, 'FAILED');
  s = step('recovery', s, 'restore');
  s = field('recovery', s, 'version', 'replacement'); s = step('recovery', s, 'reprocess');
  assert.equal(s.machine.status, 'FAILED');
  s = field('recovery', s, 'version', 'original'); s = step('recovery', s, 'reprocess');
  assert.equal(s.machine.status, 'COMPLETED');
  assert.equal(isLabComplete('recovery', s), false);
  s = step('recovery', s, 'reprocess');
  assert.equal(s.machine.publications, 1);
  assert.equal(isLabComplete('recovery', s), true);
});

test('capstone cannot complete without recovery, result audit and boundary decision', () => {
  let s = createLab('capstone');
  s = step('capstone', s, 'run');
  assert.equal(s.machine.status, 'REGISTERED');
  s = step('capstone', s, 'close'); s = step('capstone', s, 'bind');
  s = step('capstone', s, 'inject'); s = step('capstone', s, 'run');
  s = step('capstone', s, 'restore'); s = step('capstone', s, 'reprocess');
  s = field('capstone', s, 'resultCount', '9'); s = step('capstone', s, 'audit');
  assert.equal(s.achievements.includes('audit'), false);
  s = field('capstone', s, 'resultCount', '5'); s = field('capstone', s, 'integrity', 'atomic'); s = step('capstone', s, 'audit');
  assert.equal(isLabComplete('capstone', s), false);
  s = field('capstone', s, 'extension', 'comparator'); s = step('capstone', s, 'extension');
  assert.equal(isLabComplete('capstone', s), false);
  s = field('capstone', s, 'extension', 'adapter'); s = step('capstone', s, 'extension');
  assert.equal(isLabComplete('capstone', s), true);
  assert.equal(s.machine.publications, 1);
});

test('transitions do not mutate inputs, arbitrary completion cannot be configured, reset is fresh', () => {
  const original = createLab('api'); const copy = structuredClone(original);
  step('api', original, 'send');
  assert.deepEqual(original, copy);
  const forged = field('api', original, 'achievements', ['date', 'purchase', 'close', 'run', 'role']);
  assert.equal(isLabComplete('api', forged), false);
  assert.deepEqual(step('api', forged, 'reset'), createLab('api'));
  assert.equal(isLabComplete('unknown', {}), false);
});
