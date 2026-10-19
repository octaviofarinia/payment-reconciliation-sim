import { useEffect, useId, useState } from 'react';
import { ArrowCounterClockwise, ArrowLeft, ArrowRight, Cloud, Code, Compass, Database, PlugsConnected, Play, TerminalWindow } from './icons.js';
import { evidenceFor } from './lesson.js';
import './system-explorer.css';

const components = [
  {
    id: 'client', name: 'Client / generator', short: 'Client', eyebrow: 'PUBLIC CONTRACT', technology: 'Java HTTP client', icon: TerminalWindow, introduced: 2,
    owns: 'Creates synthetic purchases, registers and uploads the settlement, then independently verifies the result.',
    data: 'Purchase JSON, exact CSV bytes, expected report, and public run metadata.',
    boundary: 'Uses the demo role. It cannot publish worker reports or connect directly to MongoDB.',
    source: 'backend-generator-workflow', sourceTitle: 'Follow GenerationWorkflow',
  },
  {
    id: 'api', name: 'Spring API', short: 'API', eyebrow: 'BUSINESS STATE', technology: 'Spring Boot · EC2', icon: PlugsConnected, introduced: 2,
    owns: 'Protects the ledger, closes business dates, registers runs, and atomically persists complete reports.',
    data: 'Immutable purchases, run identities, upload instructions, and validated report submissions.',
    boundary: 'The only application component with MongoDB access. Public and internal routes require different bearer roles.',
    source: 'backend-run-service', sourceTitle: 'Inspect RunService',
  },
  {
    id: 'mongo', name: 'MongoDB Atlas', short: 'Database', eyebrow: 'DURABLE RECORDS', technology: 'Document database', icon: Database, introduced: 3,
    owns: 'Stores purchase facts, the open/closed day guard, and each run with its complete embedded report.',
    data: 'Purchases, business_days, and reconciliation_runs documents.',
    boundary: 'Only the API connects here. Local acceptance uses a MongoDB replica set; Atlas is the planned cloud database.',
    source: 'backend-run-repository', sourceTitle: 'Inspect database updates',
  },
  {
    id: 's3', name: 'Amazon S3', short: 'Object storage', eyebrow: 'VERSIONED INPUT', technology: 'Object storage · HTTPS', icon: Cloud, introduced: 4,
    owns: 'Retains the settlement bytes and their object versions, and notifies Lambda after an object is created.',
    data: 'Whole-day settlement CSV at a registered bucket/key/version; a protected worker configuration object also lives here.',
    boundary: 'Upload uses a temporary signed PUT. The worker reads via the S3 Gateway endpoint; it does not need a NAT gateway.',
    source: 'backend-upload-storage', sourceTitle: 'Inspect the upload contract',
  },
  {
    id: 'worker', name: 'Java Lambda', short: 'Worker', eyebrow: 'VALIDATE & COMPARE', technology: 'Java 21 · AWS Lambda', icon: Code, introduced: 5,
    owns: 'Verifies the exact object version, parses the whole CSV, compares the closed ledger, and submits one complete report.',
    data: 'Versioned settlement bytes, closed purchase input, comparison evidence, and a complete report.',
    boundary: 'Uses the internal worker role over private HTTP. It has no direct database edge and no settlement-write permission.',
    source: 'backend-processing-service', sourceTitle: 'Follow ProcessingService',
  },
];

const edges = [
  { id: 'public', from: 'client', to: 'api', kind: 'control', path: 'M 186 79 H 214', label: 'Public HTTP', x: 200, y: 59 },
  { id: 'database', from: 'api', to: 'mongo', kind: 'data', path: 'M 386 79 H 414', label: 'Read / write', x: 400, y: 59, both: true },
  { id: 'upload', from: 'client', to: 's3', kind: 'data', path: 'M 102 134 V 202', label: 'Direct PUT', x: 102, y: 174 },
  { id: 'notification', from: 's3', to: 'worker', kind: 'control', path: 'M 186 243 H 414', label: 'ObjectCreated event', x: 300, y: 233 },
  { id: 'version', from: 's3', to: 'worker', kind: 'data', path: 'M 186 279 H 414', label: 'Exact version bytes', x: 300, y: 297 },
  { id: 'internal', from: 'worker', to: 'api', kind: 'control', path: 'M 498 202 V 163 H 300 V 134', label: 'Worker HTTP', x: 400, y: 155 },
];

const steps = [
  {
    id: 'register', title: 'Register metadata', route: 'Client → API', kind: 'control', nodes: ['client', 'api'], edges: ['public'], source: 'backend-run-service',
    body: 'The client registers a closed prior date, SHA-256 digest, and byte length. The API records AWAITING_UPLOAD and returns instructions for an exact object key.',
    record: (reference) => `${reference} belongs to the selected business day. Registration describes the whole settlement file, not an individual reference.`,
  },
  {
    id: 'upload', title: 'Upload exact bytes', route: 'Client → S3', kind: 'data', nodes: ['client', 's3'], edges: ['upload'], source: 'backend-upload-storage',
    body: 'The client sends the CSV directly to S3 using the temporary signed PUT, required checksum, and content length. The file does not pass through Spring.',
    record: (reference, evidence) => evidence.rows.length ? `${reference} appears in ${evidence.rows.length} settlement ${evidence.rows.length === 1 ? 'row' : 'rows'} in the uploaded file.` : `${reference} has no settlement row. Its absence remains meaningful evidence; the internal purchase will arrive through the API.`,
  },
  {
    id: 'notify', title: 'Notify the worker', route: 'S3 → Lambda', kind: 'control', nodes: ['s3', 'worker'], edges: ['notification'], source: 'backend-worker-handler',
    body: 'An ObjectCreated notification identifies a bucket, key, and version ID. S3 invokes the Lambda handler; there is no queue in this architecture.',
    record: (reference) => `The event points to a file version, not ${reference} directly. The worker will discover references when it validates the file.`,
  },
  {
    id: 'version', title: 'Read the bound version', route: 'S3 → Lambda', kind: 'data', nodes: ['s3', 'worker'], edges: ['version'], source: 'backend-processing-service',
    body: 'The worker first checks API metadata, reads the event’s exact object version, verifies its actual length and digest, then binds the processing identity. Later attempts must preserve that original.',
    record: (reference) => `A later upload at the same key cannot change the original external evidence used to classify ${reference}.`,
  },
  {
    id: 'input', title: 'Read closed purchases', route: 'Lambda → API ↔ MongoDB', kind: 'control', nodes: ['worker', 'api', 'mongo'], edges: ['internal', 'database'], source: 'backend-business-date',
    body: 'The worker requests input from the internal API. The API reads immutable purchases for the CLOSED date; the worker never queries MongoDB directly.',
    record: (reference, evidence) => evidence.purchase ? `${reference} is present in the closed internal ledger with ${evidence.purchase.amountCentavos.toLocaleString('en-US')} centavos.` : `${reference} is absent from the internal ledger. The external row survives, allowing MISSING_INTERNALLY to be detected.`,
  },
  {
    id: 'compare', title: 'Validate and compare', route: 'Inside the worker', kind: 'compute', nodes: ['worker'], edges: [], source: 'backend-comparator',
    body: 'After every CSV row passes validation, the pure comparator joins the union of references, preserves evidence, and applies the five outcomes. No network call belongs inside that algorithm.',
    record: (reference, evidence) => `${reference} resolves to ${evidence.outcome.replaceAll('_', ' ')} using ${evidence.purchase ? 'one internal purchase' : 'no internal purchase'} and ${evidence.rows.length} settlement ${evidence.rows.length === 1 ? 'row' : 'rows'}.`,
  },
  {
    id: 'publish', title: 'Publish a complete report', route: 'Lambda → API → MongoDB', kind: 'control', nodes: ['worker', 'api', 'mongo'], edges: ['internal', 'database'], source: 'backend-run-repository',
    body: 'The API validates identity, complete evidence, and counts, then stores the entire bounded report and COMPLETED together in one conditional database update.',
    record: (reference) => `The report includes ${reference} with its full evidence. It becomes visible together with every other result, never as a partial publication.`,
  },
  {
    id: 'results', title: 'Read and verify results', route: 'Client → API ↔ MongoDB', kind: 'control', nodes: ['client', 'api', 'mongo'], edges: ['public', 'database'], source: 'backend-generator-workflow',
    body: 'The client polls metadata, fetches result pages only after completion, and independently compares the complete output with the expected report.',
    record: (reference) => `${reference} is one result in a five-reference canonical report. A successful upload alone could never prove this final outcome.`,
  },
];

function moduleStage(module) {
  const order = { reconciliation: 1, api: 2, stability: 3, upload: 4, worker: 5, security: 6, recovery: 7, capstone: 8 };
  return Number(module?.number) || order[module?.id] || 1;
}

function initialComponent(module) {
  return module?.id === 'stability' ? 'mongo' : module?.id === 'upload' ? 's3' : moduleStage(module) >= 5 || moduleStage(module) === 1 ? 'worker' : 'api';
}

export function SystemExplorer({ module, selected = 'AMOUNT-001', onSource }) {
  const [showFull, setShowFull] = useState(false);
  const [selectedNode, setSelectedNode] = useState(() => initialComponent(module));
  const [stepIndex, setStepIndex] = useState(null);
  const id = useId().replaceAll(':', '');
  const stage = moduleStage(module);
  const evidence = evidenceFor(selected);
  const currentStep = stepIndex === null ? null : steps[stepIndex];
  const currentNode = components.find((node) => node.id === selectedNode) || components[1];
  const isVisible = (node) => showFull || (stage === 1 ? node.id === 'worker' : node.introduced <= stage);
  const visibleNodes = components.filter(isVisible);

  useEffect(() => {
    setShowFull(false);
    setSelectedNode(initialComponent(module));
    setStepIndex(null);
  }, [module?.id]);

  function goToStep(index) {
    setShowFull(true);
    setStepIndex(index);
    setSelectedNode(steps[index].nodes[0]);
  }

  function toggleSystem() {
    setShowFull((full) => !full);
    setStepIndex(null);
    setSelectedNode(initialComponent(module));
  }

  return (
    <section className="system-explorer" aria-label="Interactive architecture explorer">
      <header className="system-heading">
        <div><p className="eyebrow">SYSTEM EXPLORER</p><h2>Follow the boundaries</h2><p className="system-intro">One business day, several responsibilities.</p></div>
        <span className="system-record"><span>Shared record</span><strong>{selected}</strong></span>
      </header>

      <div className="system-map-toolbar">
        <span className="system-map-count"><Compass size={15} aria-hidden="true" /> {showFull ? 'Complete system' : `Module ${module?.number || '01'} view`} <span>· {visibleNodes.length} of 5 components</span></span>
        <button className="system-toggle" role="switch" aria-checked={showFull} onClick={toggleSystem}><span className="system-toggle-track" aria-hidden="true"><span /></span> Full system</button>
      </div>

      <div className="system-map" aria-label="Select a component to inspect its responsibility">
        <svg className="system-connections" viewBox="0 0 600 338" preserveAspectRatio="none" aria-hidden="true">
          <defs>
            <marker id={`${id}-control`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 1 1 L 9 5 L 1 9 z" fill="#5caedc" /></marker>
            <marker id={`${id}-data`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 1 1 L 9 5 L 1 9 z" fill="#68c8ae" /></marker>
            <marker id={`${id}-active`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 1 1 L 9 5 L 1 9 z" fill="#ffbd5c" /></marker>
          </defs>
          {edges.map((edge) => {
            if (![edge.from, edge.to].every((nodeId) => visibleNodes.some((node) => node.id === nodeId))) return null;
            const active = currentStep?.edges.includes(edge.id);
            const marker = `${id}-${active ? 'active' : edge.kind}`;
            return <g key={edge.id} className={`system-edge ${edge.kind} ${active ? 'active' : ''} ${currentStep && !active ? 'faded' : ''}`}><path d={edge.path} markerEnd={`url(#${marker})`} markerStart={edge.both ? `url(#${marker})` : undefined} /><text x={edge.x} y={edge.y} textAnchor="middle">{edge.label}</text></g>;
          })}
        </svg>
        <div className="system-node-list" role="list" aria-label="System components">
          {components.map((node) => {
            const Icon = node.icon;
            const visible = isVisible(node);
            const active = currentStep?.nodes.includes(node.id);
            return <div key={node.id} role="listitem" className={`system-node-slot system-node-${node.id}`}>
              {visible ? <button className={`system-node ${selectedNode === node.id ? 'selected' : ''} ${active ? 'in-trace' : ''}`} aria-pressed={selectedNode === node.id} aria-controls={`${id}-details`} onClick={() => setSelectedNode(node.id)}><span className="system-node-top"><Icon size={21} aria-hidden="true" /><span>{stage === 1 && !showFull && node.id === 'worker' ? 'PURE DOMAIN' : node.eyebrow}</span></span><strong>{stage === 1 && !showFull && node.id === 'worker' ? 'Comparison core' : node.name}</strong><small>{stage === 1 && !showFull && node.id === 'worker' ? 'Validated values → report' : node.technology}</small>{active && <span className="system-node-trace-label">In this step</span>}</button> : <div className="system-node-placeholder"><Icon size={20} aria-hidden="true" /><span>{node.short}</span><small>Introduced in module {String(node.introduced).padStart(2, '0')}</small></div>}
            </div>;
          })}
        </div>
        <div className="system-core-note"><span>{showFull || stage >= 5 ? 'Versioned input → complete report' : 'The architecture grows with the course'}</span></div>
      </div>

      <div className="system-legend" aria-label="Diagram legend"><span><i className="system-line control" aria-hidden="true" /> Requests & events</span><span><i className="system-line data" aria-hidden="true" /> Stored data & file bytes</span><span><i className="system-selected-key" aria-hidden="true" /> Active trace</span></div>

      <section id={`${id}-details`} className="system-details" aria-labelledby={`${id}-details-title`}>
        <div className="system-details-heading"><div><p className="eyebrow">COMPONENT RESPONSIBILITY</p><h3 id={`${id}-details-title`}>{stage === 1 && !showFull && currentNode.id === 'worker' ? 'The pure comparison core' : currentNode.name}</h3></div><span className="system-detail-tech">{stage === 1 && !showFull && currentNode.id === 'worker' ? 'Plain Java' : currentNode.technology}</span></div>
        {stage === 1 && !showFull && currentNode.id === 'worker' ? <><p className="system-owner">Validated purchases and settlement rows go in. A deterministic report comes out. The comparator groups by reference and applies duplicate precedence before checking amounts.</p><p className="system-boundary">No HTTP, database, or storage calls live in the comparator. Reveal the full system to see what will eventually surround it.</p><button className="system-source" onClick={() => onSource?.('backend-comparator')}><Code size={16} aria-hidden="true" /> Inspect the comparator <ArrowRight size={15} aria-hidden="true" /></button></> : <><p className="system-owner">{currentNode.owns}</p><dl><div><dt>Data it handles</dt><dd>{currentNode.data}</dd></div><div><dt>Boundary</dt><dd>{currentNode.boundary}</dd></div></dl><button className="system-source" onClick={() => onSource?.(currentNode.source)}><Code size={16} aria-hidden="true" /> {currentNode.sourceTitle} <ArrowRight size={15} aria-hidden="true" /></button></>}
      </section>

      <section className="system-trace" aria-labelledby={`${id}-trace-title`}>
        <div className="system-trace-heading"><div><p className="eyebrow">STEP THROUGH A RUN</p><h3 id={`${id}-trace-title`}>From intent to evidence</h3></div>{currentStep ? <button className="system-reset" onClick={() => { setStepIndex(null); setSelectedNode(initialComponent(module)); }}><ArrowCounterClockwise size={15} aria-hidden="true" /> Reset trace</button> : <button className="system-start" onClick={() => goToStep(0)}><Play size={15} weight="fill" aria-hidden="true" /> Trace a run</button>}</div>
        {!currentStep && <p className="system-trace-intro">Reveal the full system and follow eight steps. Keep <strong>{selected}</strong> in view while the complete day file moves across boundaries.</p>}
        <ol className="system-step-list" aria-label="Run sequence">
          {steps.map((step, index) => <li key={step.id}><button className={`${stepIndex === index ? 'current' : ''} ${stepIndex !== null && index < stepIndex ? 'visited' : ''}`} aria-current={stepIndex === index ? 'step' : undefined} aria-label={`Step ${index + 1}: ${step.title}`} onClick={() => goToStep(index)}><span>{index + 1}</span><strong>{step.title}</strong></button></li>)}
        </ol>
        {currentStep && <div className="system-step-detail" aria-live="polite" aria-atomic="true"><div className="system-step-meta"><span>STEP {stepIndex + 1} / {steps.length}</span><span className={`system-kind ${currentStep.kind}`}>{currentStep.kind === 'data' ? 'Data transfer' : currentStep.kind === 'compute' ? 'Pure computation' : 'Control path'}</span></div><h4>{currentStep.title}</h4><p className="system-route">{currentStep.route}</p><p>{currentStep.body}</p><div className="system-reference-note"><span>{selected}</span><p>{currentStep.record(selected, evidence)}</p></div><button className="system-source" onClick={() => onSource?.(currentStep.source)}><Code size={16} aria-hidden="true" /> View this boundary in source <ArrowRight size={15} aria-hidden="true" /></button></div>}
        {currentStep && <div className="system-trace-controls"><button onClick={() => goToStep(stepIndex - 1)} disabled={stepIndex === 0}><ArrowLeft size={16} aria-hidden="true" /> Previous</button><span>{stepIndex === steps.length - 1 ? 'Complete report, preserved evidence' : `${steps.length - stepIndex - 1} steps remaining`}</span><button onClick={() => goToStep(stepIndex + 1)} disabled={stepIndex === steps.length - 1}>Next <ArrowRight size={16} aria-hidden="true" /></button></div>}
      </section>

      <footer className="system-scope"><Compass size={18} aria-hidden="true" /><div><strong>Architecture model · no cloud calls</strong><p>The source defines S3 → Lambda delivery, an S3 Gateway endpoint, and private worker → API traffic. There is no queue, NAT gateway, or worker → database connection. The deployment reference describes the AWS configuration and validation procedures.</p><button onClick={() => onSource?.('cloud-network')}>Inspect network definition <ArrowRight size={13} aria-hidden="true" /></button></div></footer>
    </section>
  );
}
