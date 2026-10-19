import { purchases, settlement, references } from './lesson.js';
import { labSpecs } from './lab-specs.js';
export { labSpecs } from './lab-specs.js';

const header = 'business_date,transaction_reference,amount_centavos,currency';
export const canonicalCsv = `${header}\n${settlement.map(row => `2026-10-01,${row.reference},${row.amountCentavos},ARS`).join('\n')}\n`;
export const canonicalMetadata = Object.freeze({ businessDate: '2026-10-01', byteLength: 3129, sha256: 'eb03cb8082c8e712a5d791ac5ce619f06585c8ae887e83611586574c1eda5ebe' });
const damagedCsv = canonicalCsv.replace('AMOUNT-001,45000,ARS', 'AMOUNT-001,45000,USD');
const defaults = {
  api: { fields: { method: 'POST', endpoint: 'date', role: 'demo' }, machine: { day: 'MISSING', purchases: 0, run: false, response: null } },
  stability: { fields: { reference: 'MATCH-001', amount: '10000' }, machine: { day: 'OPEN', records: {}, amount: null, count: 0, response: null } },
  upload: { fields: { metadata: 'exact', expiry: 'valid', checksum: 'exact' }, machine: { registered: false, versions: 0, latest: null, original: null } },
  worker: { fields: { csv: damagedCsv }, machine: { validated: false, rows: [], results: [], published: false } },
  security: { fields: { source: 'worker', destination: 'api', route: 'present', network: 'allowed', iam: 'allowed', token: 'worker' }, machine: { allowed: null, gate: 'Not tested' } },
  recovery: { fields: { version: 'original' }, machine: { status: 'PROCESSING', healthy: true, original: 'v1', publications: 0, results: [], attempts: 0 } },
  capstone: { fields: { version: 'original', resultCount: '', integrity: '', extension: '' }, machine: { day: 'OPEN', status: 'REGISTERED', healthy: true, original: null, publications: 0, results: [], attempts: 0 } },
};

export function createLab(moduleId) {
  if (!defaults[moduleId]) return null;
  return { moduleId, ...structuredClone(defaults[moduleId]), achievements: [], events: [], feedback: null };
}

export function isLabComplete(moduleId, state) {
  return Boolean(labSpecs[moduleId] && Array.isArray(state?.achievements) && labSpecs[moduleId].tasks.every(({ id }) => state.achievements.includes(id)));
}

function record(state, tone, message, earned = [], patch = {}) {
  const next = { ...state, machine: { ...state.machine, ...patch }, feedback: { tone, message } };
  next.achievements = [...new Set([...state.achievements, ...earned])];
  next.events = [...state.events, { id: (state.events.at(-1)?.id ?? 0) + 1, tone, message }].slice(-12);
  return next;
}
const good = (state, message, earned = [], patch = {}) => record(state, 'success', message, earned, patch);
const stop = (state, message, earned = [], patch = {}) => record(state, 'warning', message, earned, patch);
const resultSet = (rows) => [...new Set([...purchases.map(p => p.reference), ...rows.map(r => r.reference)])].sort().map(reference => {
  const purchase = purchases.find(p => p.reference === reference);
  const evidence = rows.filter(r => r.reference === reference);
  const outcome = evidence.length > 1 ? 'DUPLICATE' : !purchase ? 'MISSING_INTERNALLY' : !evidence.length ? 'MISSING_IN_SETTLEMENT' : purchase.amountCentavos === evidence[0].amountCentavos ? 'MATCHED' : 'AMOUNT_MISMATCH';
  return { reference, outcome, rows: evidence.map(r => r.rowNumber) };
});
const canonicalResults = () => resultSet(settlement);
const hasCanonicalRows = rows => rows.length === settlement.length && rows.every((row, i) => row.reference === settlement[i].reference && row.amountCentavos === settlement[i].amountCentavos);

// Parse the RFC 4180 shape used in this lab, including quoted fields and escaped quotes.
// Byte and signed-64-bit bounds reflect the source; textarea input is already decoded text.
function parseCsv(text) {
  if (new TextEncoder().encode(text).length > 2097152) throw new Error('FILE_TOO_LARGE: the file exceeds 2 MiB.');
  const records = []; let row = [], field = '', quoted = false, closed = false;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i += 1; }
      else if (c === '"') { quoted = false; closed = true; }
      else field += c;
    } else if (c === '"' && field === '' && !closed) quoted = true;
    else if (c === ',') { row.push(field); field = ''; closed = false; }
    else if (c === '\r' || c === '\n') {
      if (c === '\r' && text[i + 1] === '\n') i += 1;
      row.push(field); records.push(row); row = []; field = ''; closed = false;
    } else if (closed || c === '"') throw new Error('MALFORMED_CSV: check quoting and field separators.');
    else field += c;
  }
  if (quoted) throw new Error('MALFORMED_CSV: a quoted field was not closed.');
  if (field !== '' || row.length || closed) { row.push(field); records.push(row); }
  const columns = records.shift();
  if (!columns || columns.length !== 4 || columns.some((column, i) => column !== header.split(',')[i])) throw new Error('INVALID_HEADER: use the four exact column names.');
  if (records.length > 2000) throw new Error('TOO_MANY_ROWS: the worker accepts at most 2,000 settlement rows.');
  return records.map((values, index) => {
    const n = index + 1;
    if (values.length !== 4) throw new Error(`INVALID_FIELD_COUNT at row ${n}: expected four fields.`);
    if (values[0] !== '2026-10-01') throw new Error(`INVALID_DATE at row ${n}: use the registered business date.`);
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(values[1])) throw new Error(`INVALID_REFERENCE at row ${n}.`);
    if (!/^[0-9]+$/.test(values[2]) || BigInt(values[2]) <= 0n || BigInt(values[2]) > 9223372036854775807n) throw new Error(`INVALID_AMOUNT at row ${n}: use positive integer centavos.`);
    if (BigInt(values[2]) > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error(`BROWSER_MODEL_LIMIT at row ${n}: use amounts below JavaScript's exact integer limit. Java supports larger longs.`);
    if (values[3] !== 'ARS') throw new Error(`INVALID_CURRENCY at row ${n}: this project accepts ARS.`);
    return { rowNumber: n, reference: values[1], amountCentavos: Number(values[2]) };
  });
}

export function transitionLab(moduleId, supplied, action) {
  if (!defaults[moduleId]) return supplied;
  const state = supplied ?? createLab(moduleId);
  if (action.type === 'reset') return createLab(moduleId);
  if (action.type === 'configure') {
    if (!Object.hasOwn(defaults[moduleId].fields, action.key) || typeof action.value !== 'string') return state;
    const machine = moduleId === 'worker' && action.key === 'csv' ? { ...state.machine, validated: false, rows: [], results: [], published: false } : state.machine;
    return { ...state, fields: { ...state.fields, [action.key]: action.value }, machine, feedback: null };
  }
  const f = state.fields; const m = state.machine;
  if (moduleId === 'api' && action.type === 'send') {
    if (f.role === 'none') return stop(state, '401 UNAUTHORIZED · A bearer token is required.', [], { response: 401 });
    if (f.role !== 'demo') return stop(state, '403 FORBIDDEN · The worker bearer is valid for internal routes, not these public operations.', ['role'], { response: 403 });
    if (f.method === 'GET' && f.endpoint === 'run') return stop(state, '400 INVALID_REQUEST · Listing runs uses GET with a required businessDate query. This command console sends the registration body, so use POST to register.', [], { response: 400 });
    if (f.method !== 'POST') return stop(state, '405 METHOD_NOT_ALLOWED · This endpoint does not accept that method.', [], { response: 405 });
    if (f.endpoint === 'date') return good(state, `${m.day === 'MISSING' ? '201 Created' : '200 Unchanged replay'} · Business date 2026-10-01.`, ['date'], { day: m.day === 'MISSING' ? 'OPEN' : m.day, response: m.day === 'MISSING' ? 201 : 200 });
    if (f.endpoint === 'purchase') {
      if (m.purchases) return good(state, '200 Unchanged replay · The same reference and business fields already exist.', [], { response: 200 });
      if (m.day === 'MISSING') return stop(state, '404 NOT_FOUND · Create the business date before a new purchase.', [], { response: 404 });
      if (m.day !== 'OPEN') return stop(state, '409 CONFLICT · A new purchase requires an open business date.', [], { response: 409 });
      return good(state, '201 Created · MATCH-001 stored with 10,000 centavos.', ['purchase'], { purchases: 1, response: 201 });
    }
    if (f.endpoint === 'close') {
      if (m.day === 'MISSING') return stop(state, '404 NOT_FOUND · Create the date before closing it.', [], { response: 404 });
      return good(state, '200 OK · The day is CLOSED. New references can no longer be added.', ['close'], { day: 'CLOSED', response: 200 });
    }
    if (f.endpoint === 'run') {
      if (m.day === 'MISSING') return stop(state, '404 NOT_FOUND · Create the business date first.', [], { response: 404 });
      if (m.day !== 'CLOSED') return stop(state, '409 CONFLICT · A run requires a closed business date.', [], { response: 409 });
      return good(state, `${m.run ? '200 Unchanged replay' : '201 Created'} · Run registered; upload information returned.`, ['run'], { run: true, response: m.run ? 200 : 201 });
    }
  }
  if (moduleId === 'stability') {
    if (action.type === 'close') return good(state, 'The day is CLOSED. Existing records remain immutable; identical replays still succeed.', ['close'], { day: 'CLOSED' });
    if (action.type === 'write') {
      if (!/^[0-9]+$/.test(f.amount) || !Number.isSafeInteger(Number(f.amount)) || Number(f.amount) <= 0) return stop(state, '400 INVALID_REQUEST · Amount must be positive integer centavos.');
      const amount = Number(f.amount); const existing = m.records[f.reference];
      if (existing !== undefined) return existing === amount ? good(state, '200 Unchanged replay · No second record was inserted.', ['replay'], { response: 200 }) : stop(state, '409 CONFLICT · That reference already has different business fields. The stored amount is unchanged.', ['conflict'], { response: 409 });
      if (m.day === 'CLOSED') return stop(state, '409 CONFLICT · New references are rejected after closure. The input remains stable.', ['late'], { response: 409 });
      const records = { ...m.records, [f.reference]: amount };
      return good(state, `201 Created · ${f.reference} stores ${amount.toLocaleString('en-US')} centavos.`, f.reference === 'MATCH-001' && amount === 10000 ? ['create'] : [], { records, amount: records['MATCH-001'] ?? null, count: Object.keys(records).length, response: 201 });
    }
  }
  if (moduleId === 'upload') {
    if (action.type === 'register') {
      if (f.metadata !== 'exact') return stop(state, 'Metadata mismatch in this exercise. Register the exact 3129-byte fixture and its SHA-256; a later byte/checksum check would reject different content.');
      return good(state, 'Registered · 3129 bytes + SHA-256 of the exact fixture. The API returns a scoped, expiring presigned upload.', ['register'], { registered: true });
    }
    if (action.type === 'upload') {
      if (!m.registered) return stop(state, 'Register the upload metadata before sending bytes directly to S3.');
      if (f.expiry === 'expired') return stop(state, 'Upload rejected · The presigned request has expired. No new object version was created.', ['reject']);
      if (f.checksum !== 'exact') return stop(state, 'Upload rejected · Bytes do not match the signed checksum. No new object version was created.', ['reject']);
      const latest = `v${m.versions + 1}`;
      return good(state, `Direct S3 upload accepted · ${latest} created. ObjectCreated can notify the worker; the API did not proxy the file.`, [], { versions: m.versions + 1, latest });
    }
    if (action.type === 'bind') {
      if (!m.latest) return stop(state, 'No uploaded object exists yet.');
      if (m.original && m.original !== m.latest) return stop(state, `INPUT_IDENTITY_MISMATCH · ${m.latest} cannot replace bound ${m.original}. Recovery must use the retained original.`, ['replacement']);
      return good(state, `Verified ${m.latest} and bound its identity atomically. Version + checksum make retries reproducible.`, ['bind'], { original: m.latest });
    }
  }
  if (moduleId === 'worker') {
    if (action.type === 'validate') {
      try {
        const rows = parseCsv(f.csv); const canonical = hasCanonicalRows(rows);
        return good(state, `${rows.length} rows validated before comparison.${canonical ? ' The canonical input is restored.' : ' Valid scratch input; restore the canonical 100 rows to meet this mission’s target.'}`, canonical ? ['validate'] : [], { validated: true, rows, results: [], published: false });
      } catch (error) { return stop(state, `${error.message} The entire file is rejected; no partial report is published.`, ['reject'], { validated: false, rows: [], results: [], published: false }); }
    }
    if (action.type === 'compare') {
      if (!m.validated) return stop(state, 'Validate the entire current file first. Comparison cannot consume partially valid rows.');
      const results = resultSet(m.rows); const canonical = hasCanonicalRows(m.rows);
      return good(state, `${results.length} distinct references classified. Duplicate row count takes precedence over matching amounts.`, canonical ? ['compare'] : [], { results, published: false });
    }
    if (action.type === 'publish') {
      if (!m.validated || !m.results.length) return stop(state, 'No complete validated report is available. Validate, then compare.');
      return good(state, `${m.results.length} results published together. Readers observe the complete report, never a partial batch.`, hasCanonicalRows(m.rows) ? ['publish'] : [], { published: true });
    }
  }
  if (moduleId === 'security' && action.type === 'probe') {
    if (f.source === 'worker' && f.destination === 'atlas') return stop(state, 'Blocked by architecture · The private worker has no internet default route and no Atlas connection. It reads purchase input through the API.', ['atlas'], { allowed: false, gate: 'Architecture boundary' });
    if (f.source === 'public' && f.destination === 'api') return stop(state, 'Blocked by security group · Port 8080 admits the worker security group. A developer uses the separate SSH tunnel path.', ['public'], { allowed: false, gate: 'Security group' });
    if (f.source !== 'worker') return stop(state, 'This probe models worker reads and public API access. External presigned S3 uploads belong to the upload lab.', [], { allowed: false, gate: 'Outside this probe' });
    if (f.route !== 'present') return stop(state, 'No route · Authorization cannot repair a missing network path.', ['gate'], { allowed: false, gate: 'Route' });
    if (f.network !== 'allowed') return stop(state, 'Network rule blocked · Allow the intended API port or S3 regional prefix-list egress.', ['gate'], { allowed: false, gate: 'Network rule' });
    if (f.destination === 'api') {
      if (f.token !== 'worker') return stop(state, `${f.token === 'none' ? '401 UNAUTHORIZED' : '403 FORBIDDEN'} · Network reachability is present; the internal HTTP route still requires the worker bearer.`, ['gate'], { allowed: false, gate: 'Application role' });
      return good(state, 'Allowed · VPC local route → private API:8080 → worker bearer. S3 IAM does not authorize this HTTP endpoint.', ['api'], { allowed: true, gate: 'Private API reached' });
    }
    if (f.destination === 's3') {
      if (f.iam !== 'allowed') return stop(state, 'AccessDenied · The gateway route exists, but the scoped S3 permission is missing. An HTTP bearer cannot replace IAM.', ['gate'], { allowed: false, gate: 'S3 IAM / endpoint policy' });
      return good(state, 'Allowed · S3 gateway endpoint + regional egress + scoped S3 IAM. A NAT gateway is not needed for this path.', ['s3'], { allowed: true, gate: 'S3 object reached' });
    }
  }
  if (moduleId === 'recovery' || moduleId === 'capstone') {
    const capstone = moduleId === 'capstone';
    if (capstone && action.type === 'close') return good(state, 'Day CLOSED · The canonical 80 purchases are now the stable comparison input.', ['close'], { day: 'CLOSED' });
    if (capstone && action.type === 'bind') {
      if (m.day !== 'CLOSED') return stop(state, 'Close the date before binding this run’s input.');
      if (f.version !== 'original') return stop(state, 'Choose retained original v1. Replacement v2 is not the registered input for this exercise.');
      return good(state, 'Original v1 bound · The run identifies one immutable settlement input.', ['bind'], { original: 'v1' });
    }
    if (action.type === 'inject') {
      if (m.status === 'COMPLETED') return stop(state, 'The report is already complete. Replay it to observe idempotency, or reset the mission to inject a new failure.');
      return good(state, 'Report endpoint failure armed. Run an attempt to observe its effect; the diagnostic callback remains available in this scenario.', [], { healthy: false });
    }
    if (action.type === 'restore') return good(state, 'API restored. Recovery is explicit; restoring availability alone does not finish the run.', state.achievements.includes('failure') ? ['restore'] : [], { healthy: true });
    if (action.type === 'attempt' || action.type === 'run' || action.type === 'reprocess') {
      if (capstone && (m.day !== 'CLOSED' || !m.original)) return stop(state, 'Close the day and bind its original before processing.');
      if (m.status === 'COMPLETED') return good(state, '200 Completed replay · The report is unchanged. No second publication or invocation is needed.', state.achievements.includes('recover') ? ['replay'] : []);
      if (action.type === 'reprocess' && f.version !== 'original') return stop(state, 'ORIGINAL_UNAVAILABLE · Replacement is forbidden. Select the retained original to recover.');
      if (!m.healthy) return stop(state, 'FAILED · API_FAILURE, retryable=true. The diagnostic callback recorded the failed attempt; no results were published.', ['failure'], { status: 'FAILED', attempts: m.attempts + 1 });
      const recovered = action.type === 'reprocess' && state.achievements.includes('failure');
      return good(state, `${action.type === 'reprocess' ? '202 accepted → worker executes → ' : ''}COMPLETED · 100 results published from original v1. This step condenses the asynchronous execution.`, recovered ? ['recover'] : [], { status: 'COMPLETED', publications: 1, results: canonicalResults(), attempts: m.attempts + 1 });
    }
    if (capstone && action.type === 'audit') {
      if (m.status !== 'COMPLETED') return stop(state, 'Complete the run before auditing its report.');
      if (f.resultCount !== String(references.length) || f.integrity !== 'atomic') return stop(state, 'Revisit the evidence: 80 purchases and 100 rows form 100 distinct references. The complete report is published atomically.');
      return good(state, 'Audit passed · 100 distinct references, one result per reference, one complete atomic report.', ['audit']);
    }
    if (capstone && action.type === 'extension') {
      if (f.extension !== 'adapter') return stop(state, 'A new storage transport belongs behind the storage adapter boundary. Keep the pure comparator unchanged; add adapter contract tests.');
      return good(state, 'Extension mapped · Add/replace the storage adapter and wiring; retain parser/comparator behavior and verify the adapter contract.', ['extension']);
    }
  }
  return state;
}
