import { ArrowRight, ArrowCounterClockwise, CheckCircle, WarningCircle, Circle } from './icons.js';
import { canonicalCsv, canonicalMetadata, labSpecs, isLabComplete } from './simulations.js';
import { evidenceFor } from './lesson.js';
import { money } from './presentation.js';
import './simulations.css';

const endpointNames = {
  date: '/api/v1/business-dates', purchase: '/api/v1/transactions',
  close: '/api/v1/business-dates/2026-10-01/close', run: '/api/v1/reconciliation-runs',
};

function SelectField({ label, name, value, onChange, children, help }) {
  return <label className="lab-field"><span>{label}</span><select name={name} value={value} onChange={event => onChange(name, event.target.value)}>{children}</select>{help && <small>{help}</small>}</label>;
}
function Options({ items }) { return items.map(([value, label]) => <option key={value} value={value}>{label}</option>); }
function LabButton({ children, action, onAction, primary = false }) { return <button className={primary ? 'primary-button lab-action' : 'secondary-button lab-action'} onClick={() => onAction({ type: action })}>{children}{primary && <ArrowRight size={16} />}</button>; }
function Stat({ label, value }) { return <div className="lab-stat"><span>{label}</span><strong>{value}</strong></div>; }
function Steps({ labels, active }) { return <ol className="lab-pipeline" aria-label="Processing stages">{labels.map((label, i) => <li key={label} className={i <= active ? 'reached' : ''}><span>{i + 1}</span>{label}</li>)}</ol>; }

function RequestLab({ lab, onAction, configure }) {
  const f = lab.fields; const m = lab.machine;
  const bodies = { date: { businessDate: '2026-10-01' }, purchase: { transactionReference: 'MATCH-001', merchantId: 'MERCHANT-001', businessDate: '2026-10-01', amountCentavos: 10000, currency: 'ARS' }, close: null, run: canonicalMetadata };
  return <>
    <Steps labels={['Open date', 'Store purchase', 'Close date', 'Register run']} active={m.run ? 3 : m.day === 'CLOSED' ? 2 : m.purchases ? 1 : m.day === 'OPEN' ? 0 : -1} />
    <div className="lab-form-grid"><SelectField label="Method" name="method" value={f.method} onChange={configure}><Options items={['POST', 'GET', 'PUT'].map(x => [x, x])} /></SelectField><SelectField label="Bearer role" name="role" value={f.role} onChange={configure}><Options items={[["demo", "Public / demo"], ["worker", "Internal / worker"], ["none", "No token"]]} /></SelectField><SelectField label="Endpoint" name="endpoint" value={f.endpoint} onChange={configure}><Options items={[["date", "Create business date"], ["purchase", "Create purchase"], ["close", "Close business date"], ["run", "Register reconciliation run"]]} /></SelectField></div>
    <div className="lab-request"><div className="lab-code-heading"><span>{f.method}</span><code>{endpointNames[f.endpoint]}</code></div><pre>{bodies[f.endpoint] ? JSON.stringify(bodies[f.endpoint], null, 2) : 'No request body'}</pre><p className="lab-note">The registration checksum describes the exact canonical CSV. No bearer or remote endpoint is used.</p></div>
    <LabButton action="send" onAction={onAction} primary>Send simulated request</LabButton>
    <div className="lab-stats"><Stat label="Business date" value={m.day} /><Stat label="Purchases" value={m.purchases} /><Stat label="Last HTTP status" value={m.response ?? 'Not sent'} /></div>
  </>;
}

function StabilityLab({ lab, onAction, configure }) {
  return <><div className="lab-stats"><Stat label="Date state" value={lab.machine.day} /><Stat label="Stored records" value={lab.machine.count} /><Stat label="MATCH-001 amount" value={lab.machine.amount == null ? 'Not stored' : `${lab.machine.amount.toLocaleString('en-US')} ¢`} /></div>
    <div className="lab-form-grid"><SelectField label="Transaction reference" name="reference" value={lab.fields.reference} onChange={configure}><Options items={[["MATCH-001", "MATCH-001 · original"], ["NEW-001", "NEW-001 · new reference"]]} /></SelectField><SelectField label="Amount in centavos" name="amount" value={lab.fields.amount} onChange={configure}><Options items={[["10000", "10,000 · ARS 100.00"], ["11000", "11,000 · ARS 110.00"], ["0", "0 · invalid input"]]} /></SelectField></div>
    <div className="lab-actions"><LabButton action="write" onAction={onAction} primary>Create / replay purchase</LabButton><LabButton action="close" onAction={onAction}>Close business date</LabButton></div>
    <div className="lab-observation"><strong>The invariant</strong><p>Identical business fields replay the existing record. A changed amount conflicts. Once the date is closed, a new reference cannot enter the comparison input.</p></div>
    <p className="lab-note">Use the original reference twice, change its amount, then close the day and try NEW-001. Reset if you close before creating the first record.</p>
  </>;
}

function UploadLab({ lab, onAction, configure }) {
  const m = lab.machine;
  return <><Steps labels={['Register', 'Direct upload', 'Verify bytes', 'Bind original']} active={m.original ? 3 : m.latest ? 1 : m.registered ? 0 : -1} />
    <div className="lab-form-grid"><SelectField label="Registration metadata" name="metadata" value={lab.fields.metadata} onChange={configure}><Options items={[["exact", "Exact fixture · 211 bytes + SHA-256"], ["changed", "Different length / checksum"]]} /></SelectField><SelectField label="Presigned request" name="expiry" value={lab.fields.expiry} onChange={configure}><Options items={[["valid", "Within validity window"], ["expired", "Expired"]]} /></SelectField><SelectField label="Uploaded bytes" name="checksum" value={lab.fields.checksum} onChange={configure}><Options items={[["exact", "Match signed checksum"], ["changed", "Altered after signing"]]} /></SelectField></div>
    <div className="lab-actions"><LabButton action="register" onAction={onAction}>1 · Register metadata</LabButton><LabButton action="upload" onAction={onAction} primary>2 · Upload directly to S3</LabButton><LabButton action="bind" onAction={onAction}>3 · Deliver event and bind</LabButton></div>
    <div className="lab-stats"><Stat label="Object versions" value={m.versions} /><Stat label="Latest uploaded" value={m.latest ?? 'None'} /><Stat label="Bound original" value={m.original ?? 'Unbound'} /></div>
    <div className="lab-observation"><strong>Two identities to keep separate</strong><p>The latest object version is not automatically the run’s input. After binding, the run retains its original version even when another upload creates a newer one.</p></div>
  </>;
}

function ResultTable({ results, selected, onSelect }) {
  if (!results.length) return <p className="lab-empty">No report exists yet. Results appear only after the complete input has passed validation.</p>;
  return <div className="lab-results" role="group" aria-label="Simulated reconciliation results">{results.map(result => <button key={result.reference} aria-pressed={selected === result.reference} className={selected === result.reference ? 'selected' : ''} onClick={() => onSelect?.(result.reference)}><span>{result.reference}</span><span>{result.outcome.replaceAll('_', ' ').toLowerCase()}</span></button>)}</div>;
}

function WorkerLab({ lab, onAction, configure, selected, onSelect }) {
  return <><Steps labels={['Validate whole file', 'Compare references', 'Publish report']} active={lab.machine.published ? 2 : lab.machine.results.length ? 1 : lab.machine.validated ? 0 : -1} />
    <label className="lab-field lab-csv"><span>Settlement CSV · editable scratch input</span><textarea rows="8" spellCheck="false" value={lab.fields.csv} onChange={event => configure('csv', event.target.value)} maxLength="12000" aria-describedby="csv-help" /></label>
    <p id="csv-help" className="lab-note">The initial defect is USD on settlement row 3; this project accepts ARS. Maximum editor length: 12,000 characters. The Java worker accepts up to 2 MiB and 2,000 rows.</p>
    <div className="lab-actions"><LabButton action="validate" onAction={onAction} primary>Validate file</LabButton><LabButton action="compare" onAction={onAction}>Compare all references</LabButton><LabButton action="publish" onAction={onAction}>Publish atomic report</LabButton><button className="text-button" onClick={() => configure('csv', canonicalCsv)}>Restore valid canonical CSV</button></div>
    <ResultTable results={lab.machine.results} selected={selected} onSelect={onSelect} />
    <div className="lab-stats"><Stat label="Input" value={lab.machine.validated ? 'VALIDATED' : 'UNVALIDATED'} /><Stat label="Results" value={lab.machine.results.length} /><Stat label="Publication" value={lab.machine.published ? 'ATOMIC / COMPLETE' : 'NONE'} /></div>
  </>;
}

function SecurityLab({ lab, onAction, configure }) {
  const f = lab.fields;
  return <><div className="lab-route"><strong>{f.source === 'worker' ? 'Private Lambda worker' : 'Public internet'}</strong><ArrowRight size={22} /><strong>{f.destination === 'api' ? 'EC2 API :8080' : f.destination === 's3' ? 'Regional S3' : 'MongoDB Atlas'}</strong></div>
    <div className="lab-form-grid"><SelectField label="Source" name="source" value={f.source} onChange={configure}><Options items={[["worker", "Private Lambda worker"], ["public", "Public internet"]]} /></SelectField><SelectField label="Destination" name="destination" value={f.destination} onChange={configure}><Options items={[["api", "Private API"], ["s3", "S3 settlement object"], ["atlas", "MongoDB Atlas"]]} /></SelectField></div>
    <fieldset className="lab-gates"><legend>Connection gates</legend><div className="lab-form-grid"><SelectField label="Route" name="route" value={f.route} onChange={configure} help={f.destination === 's3' ? 'S3 gateway endpoint route' : 'VPC local route for the API'}><Options items={[["present", "Intended route present"], ["blocked", "Route missing"]]} /></SelectField><SelectField label="Network rule" name="network" value={f.network} onChange={configure}><Options items={[["allowed", "Intended port / egress allowed"], ["blocked", "Network rule blocked"]]} /></SelectField><SelectField label="S3 IAM + endpoint policy" name="iam" value={f.iam} onChange={configure} help="Applies to the S3 path"><Options items={[["allowed", "Scoped read allowed"], ["denied", "Read permission missing"]]} /></SelectField><SelectField label="Application bearer" name="token" value={f.token} onChange={configure} help="Applies to the internal HTTP API"><Options items={[["worker", "Worker role"], ["demo", "Public / demo role"], ["none", "No token"]]} /></SelectField></div></fieldset>
    <LabButton action="probe" onAction={onAction} primary>Test connection</LabButton>
    <div className="lab-stats"><Stat label="Connection" value={lab.machine.allowed == null ? 'NOT TESTED' : lab.machine.allowed ? 'ALLOWED' : 'BLOCKED'} /><Stat label="Decision at" value={lab.machine.gate} /></div>
    <p className="lab-note">Counterfactual controls test missing gates; they do not alter the project’s topology. This browser model does not contact AWS or Atlas.</p>
  </>;
}

function RecoveryLab({ lab, onAction, configure, selected, onSelect, capstone }) {
  const f = lab.fields; const m = lab.machine;
  return <><Steps labels={capstone ? ['Close + bind', 'Fail an attempt', 'Recover original', 'Audit report'] : ['Fail an attempt', 'Restore API', 'Recover original', 'Replay completion']} active={capstone ? lab.achievements.includes('audit') ? 3 : lab.achievements.includes('recover') ? 2 : lab.achievements.includes('failure') ? 1 : m.original ? 0 : -1 : lab.achievements.includes('replay') ? 3 : lab.achievements.includes('recover') ? 2 : lab.achievements.includes('restore') ? 1 : lab.achievements.includes('failure') ? 0 : -1} />
    <div className="lab-stats"><Stat label="Run status" value={m.status} /><Stat label="Report endpoint" value={m.healthy ? 'AVAILABLE' : 'FAILURE ARMED'} /><Stat label="Publications" value={m.publications} /></div>
    <SelectField label="Input used for recovery" name="version" value={f.version} onChange={configure}><Options items={[["original", "v1 · retained original"], ["replacement", "v2 · replacement, original unavailable"]]} /></SelectField>
    {capstone && <div className="lab-actions"><LabButton action="close" onAction={onAction}>Close canonical day</LabButton><LabButton action="bind" onAction={onAction}>Bind original v1</LabButton></div>}
    <div className="lab-actions"><LabButton action="inject" onAction={onAction}>Inject report failure</LabButton><LabButton action={capstone ? 'run' : 'attempt'} onAction={onAction}>Run worker attempt</LabButton><LabButton action="restore" onAction={onAction}>Restore API</LabButton><LabButton action="reprocess" onAction={onAction} primary>Reprocess run</LabButton></div>
    <p className="lab-note">Start by injecting a failure, then run the worker. In this controlled scenario the failure callback remains reachable. An outage that also blocks the callback can leave metadata incomplete.</p>
    <ResultTable results={m.results} selected={selected} onSelect={onSelect} />
    {capstone && <>
      <section className="lab-audit"><h2>Audit the finished report</h2><p>Use the published evidence above. How many results belong in this day, and what must a reader observe?</p><div className="lab-form-grid"><SelectField label="Expected result count" name="resultCount" value={f.resultCount} onChange={configure}><Options items={[["", "Choose a count"], ["4", "4 · purchase count"], ["5", "5 · distinct references"], ["9", "9 · all input rows"]]} /></SelectField><SelectField label="Publication rule" name="integrity" value={f.integrity} onChange={configure}><Options items={[["", "Choose a guarantee"], ["atomic", "The complete report appears atomically"], ["partial", "Partial results become visible as rows finish"]]} /></SelectField></div><LabButton action="audit" onAction={onAction}>Verify report integrity</LabButton></section>
      <section className="lab-audit"><h2>Extend the project</h2><p>A second storage transport must supply the same settlement bytes. Which boundary should change while the reconciliation rules remain stable?</p><SelectField label="Extension boundary" name="extension" value={f.extension} onChange={configure}><Options items={[["", "Choose a boundary"], ["adapter", "Storage adapter + wiring + adapter contract tests"], ["comparator", "Pure comparison rules and outcome precedence"], ["controller", "Purchase controller and purchase schema"]]} /></SelectField><LabButton action="extension" onAction={onAction}>Check extension plan</LabButton></section>
    </>}
  </>;
}

export function SimulationLab({ moduleId, lab, onAction, selected, onSelect }) {
  const spec = labSpecs[moduleId];
  if (!spec || !lab) return null;
  const configure = (key, value) => onAction({ type: 'configure', key, value });
  const complete = isLabComplete(moduleId, lab); const evidence = evidenceFor(selected);
  const shared = { lab, onAction, configure, selected, onSelect };
  return <article className="simulation-lab">
    <div className="content-kicker"><span>INTERACTIVE MISSION</span><span className="lab-sim-badge">Browser simulation</span></div>
    <h1>{spec.title}</h1><p className="lead">{spec.intro}</p>
    <div className="lab-selected"><span className="eyebrow">SHARED REFERENCE</span><strong>{selected}</strong><span>{evidence.purchase ? `ARS ${money(evidence.purchase.amountCentavos)}` : 'No internal purchase'} · {evidence.rows.length} settlement {evidence.rows.length === 1 ? 'row' : 'rows'}</span></div>
    <details className="lab-checklist"><summary>{lab.achievements.filter(id => spec.tasks.some(t => t.id === id)).length} / {spec.tasks.length} mission tasks · view checklist</summary><ul>{spec.tasks.map(t => <li key={t.id} className={lab.achievements.includes(t.id) ? 'done' : ''}>{lab.achievements.includes(t.id) ? <CheckCircle size={17} /> : <Circle size={17} />}<span>{t.label}</span></li>)}</ul></details>
    <div className="lab-workspace">
      {moduleId === 'api' && <RequestLab {...shared} />}
      {moduleId === 'stability' && <StabilityLab {...shared} />}
      {moduleId === 'upload' && <UploadLab {...shared} />}
      {moduleId === 'worker' && <WorkerLab {...shared} />}
      {moduleId === 'security' && <SecurityLab {...shared} />}
      {(moduleId === 'recovery' || moduleId === 'capstone') && <RecoveryLab {...shared} capstone={moduleId === 'capstone'} />}
    </div>
    <div className="lab-feedback-region" aria-live="polite" aria-atomic="true">{lab.feedback && <div className={`lab-feedback ${lab.feedback.tone}`}>{lab.feedback.tone === 'success' ? <CheckCircle size={21} /> : <WarningCircle size={21} />}<p>{lab.feedback.message}</p></div>}</div>
    {complete && <div className="lab-earned"><CheckCircle size={23} weight="fill" /><div><strong>Mission complete</strong><p>Your evidence is saved. Keep experimenting or continue the course.</p></div></div>}
    <details className="lab-event-log"><summary>Event log · {lab.events.length} recent observations</summary>{lab.events.length ? <ol>{[...lab.events].reverse().map(event => <li key={event.id}><span className="lab-event-number">{String(event.id).padStart(2, '0')}</span><p>{event.message}</p></li>)}</ol> : <p className="lab-note">Run an action to inspect the decision and its consequence.</p>}</details>
    <button className="quiet-button lab-reset" onClick={() => onAction({ type: 'reset' })}><ArrowCounterClockwise size={16} /> Reset this mission</button>
  </article>;
}
