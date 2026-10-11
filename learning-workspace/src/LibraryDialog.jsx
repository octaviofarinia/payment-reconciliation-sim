import { useEffect, useId, useRef, useState } from 'react';
import { modules, glossary, sources } from './content/course.js';
import { courseStats } from './progress.js';
import { ArrowRight, BookOpen, CheckCircle, Code, DownloadSimple, MagnifyingGlass, TerminalWindow, UploadSimple, X } from './icons.js';
import './library.css';

const sections = ['Glossary', 'Local labs', 'Bookmarks', 'Progress', 'Evidence', 'Sources'];
const sectionDescriptions = {
  Glossary: 'Plain-language explanations, connected to the lesson where each idea becomes useful.',
  'Local labs': 'Optional exercises in the actual Java project. The complete core course works in this browser.',
  Bookmarks: 'Return to the lessons you saved. Your selected reference stays with you.',
  Progress: 'Assessed missions and opened lessons are recorded separately. Opening a page is not an assessment.',
  Evidence: 'Know what the course demonstrates, what local tests prove, and what remains to be observed in the cloud.',
  Sources: 'Read snapshots of the project files behind the lessons. Each source keeps its original repository path.',
};

export function LibraryDialog({ open, section = 'Glossary', query = '', onClose, state, onNavigate, onSource, onExport, onImport, onResetAll, onDownloadReport, notice }) {
  const dialog = useRef(null);
  const returnFocus = useRef(null);
  const closeButton = useRef(null);
  const searchInput = useRef(null);
  const fileInput = useRef(null);
  const wasOpen = useRef(false);
  const suppressReturn = useRef(false);
  const headingId = useId();
  const searchId = useId();
  const labSelectId = useId();
  const [active, setActive] = useState(sections.includes(section) ? section : 'Glossary');
  const [search, setSearch] = useState(query);
  const [labId, setLabId] = useState(state.moduleId);
  const [message, setMessage] = useState('');
  const [importing, setImporting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setActive(sections.includes(section) ? section : 'Glossary');
    setSearch(query || '');
    setMessage('');
  }, [open, section, query]);

  useEffect(() => {
    if (open) setLabId(state.moduleId);
  }, [open, state.moduleId]);

  useEffect(() => {
    const element = dialog.current;
    if (open && !element.open) {
      returnFocus.current = document.activeElement;
      suppressReturn.current = false;
      element.showModal();
      closeButton.current?.focus();
      wasOpen.current = true;
    } else if (!open && wasOpen.current) {
      if (element.open) element.close();
      wasOpen.current = false;
      if (!suppressReturn.current && returnFocus.current?.isConnected && !document.querySelector('dialog[open]')) returnFocus.current.focus();
    }
  }, [open]);

  const close = (restoreFocus = true) => {
    suppressReturn.current = !restoreFocus;
    if (dialog.current.open) dialog.current.close();
    onClose();
  };
  const navigate = (moduleId, step = 0) => {
    close(false);
    onNavigate({ moduleId, step, mode: 'Course', overview: false });
  };
  const viewSource = (id) => {
    // Close the library synchronously before opening another native modal.
    close(false);
    onSource(id);
  };
  const chooseSection = (next) => {
    setActive(next);
    setSearch('');
    setMessage('');
  };
  const copyCommand = async (command) => {
    try {
      await navigator.clipboard.writeText(command);
      setMessage('Command copied. Run it from the Java repository root.');
    } catch {
      setMessage('Clipboard access is unavailable. Select and copy the command text instead.');
    }
  };
  const importFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setImporting(true);
    setMessage('');
    try { await onImport(file); }
    catch (error) { setMessage(error instanceof Error ? error.message : 'This progress file could not be restored.'); }
    finally { setImporting(false); }
  };

  const normalized = search.trim().toLocaleLowerCase();
  const terms = glossary.filter((term) => `${term.term} ${term.definition}`.toLocaleLowerCase().includes(normalized)).sort((a, b) => a.term.localeCompare(b.term));
  const matchingSources = sources.filter((source) => `${source.title} ${source.path} ${source.note} ${source.group}`.toLocaleLowerCase().includes(normalized));
  const labModule = modules.find((module) => module.id === labId) || modules[0];
  const stats = courseStats(state);
  const lessonCount = modules.reduce((count, module) => count + module.lessons.length, 0);
  const saved = state.bookmarks.map((key) => {
    const [moduleId, position] = key.split(':');
    const module = modules.find((item) => item.id === moduleId);
    const step = Number(position);
    return module?.lessons[step] ? { key, module, lesson: module.lessons[step], step } : null;
  }).filter(Boolean);
  const statusMessage = message || (typeof notice === 'string' ? notice : notice?.message);

  return <dialog ref={dialog} className="library-dialog" aria-labelledby={headingId} onCancel={(event) => { event.preventDefault(); close(); }} onClick={(event) => { if (event.target === event.currentTarget) close(); }}>
    <div className="library-shell">
      <header className="library-header">
        <div><p className="eyebrow">YOUR FIELD GUIDE</p><h2 id={headingId}><BookOpen size={24} aria-hidden="true" /> Learning library</h2></div>
        <button ref={closeButton} autoFocus className="icon-button" aria-label="Close learning library" onClick={() => close()}><X size={22} aria-hidden="true" /></button>
      </header>
      <nav className="library-tabs" aria-label="Library sections">{sections.map((item) => <button key={item} type="button" aria-current={active === item ? 'page' : undefined} className={active === item ? 'active' : ''} onClick={() => chooseSection(item)}>{item}{item === 'Bookmarks' && saved.length > 0 && <span className="library-tab-count">{saved.length}</span>}</button>)}</nav>
      <div className="library-body">
        <div className="library-section-heading"><p className="eyebrow">{active === 'Local labs' ? 'TAKE IT INTO THE REPOSITORY' : 'RECONCILIATION LAB'}</p><h3>{active}</h3><p>{sectionDescriptions[active]}</p></div>
        {statusMessage && <p className="library-notice" role="status">{statusMessage}</p>}

        {(active === 'Glossary' || active === 'Sources') && <div className="library-search"><label htmlFor={searchId}>{active === 'Glossary' ? 'Search terms and definitions' : 'Search sources, paths and notes'}</label><div><MagnifyingGlass size={19} aria-hidden="true" /><input ref={searchInput} id={searchId} type="search" value={search} placeholder={active === 'Glossary' ? 'Try “idempotency” or “centavos”' : 'Try “worker”, “IAM” or “RunService”'} onChange={(event) => setSearch(event.target.value)} /></div><p aria-live="polite">{active === 'Glossary' ? `${terms.length} terms` : `${matchingSources.length} source files`}{normalized && ' found'}</p></div>}

        {active === 'Glossary' && (terms.length ? <dl className="library-glossary">{terms.map((term) => {
          const module = modules.find((item) => item.id === term.moduleId);
          const step = Math.max(0, module?.lessons.findIndex((lesson) => lesson.concepts?.includes(term.id)) ?? 0);
          return <div key={term.id}><dt>{term.term}</dt><dd>{term.definition}</dd>{module && <dd className="library-definition-link"><button className="text-button" onClick={() => navigate(module.id, step)}>{module.number} · {module.title}<ArrowRight size={14} aria-hidden="true" /></button></dd>}</div>;
        })}</dl> : <EmptyState title="No matching terms" text="Try a shorter term, or clear the search to browse every definition." onClear={() => setSearch('')} />)}

        {active === 'Sources' && (matchingSources.length ? <div className="library-sources">{matchingSources.map((source) => <article key={source.id}><div className="library-source-meta"><span>{source.group}</span><span>Lines {source.focus[0]}–{source.focus[1]}</span></div><h4><button onClick={() => viewSource(source.id)}>{source.title}<Code size={18} aria-hidden="true" /></button></h4><p className="library-source-path">{source.path}</p><p>{source.note}</p><button className="text-button" onClick={() => viewSource(source.id)}>Read source<ArrowRight size={14} aria-hidden="true" /></button></article>)}</div> : <EmptyState title="No matching source files" text="Try a component name, a repository path or a technology." onClear={() => setSearch('')} />)}

        {active === 'Local labs' && <div className="library-labs">
          <label className="library-select-label" htmlFor={labSelectId}>Choose a module</label><select id={labSelectId} value={labModule.id} onChange={(event) => { setLabId(event.target.value); setMessage(''); }}>{modules.map((module) => <option key={module.id} value={module.id}>{module.number} · {module.title}</option>)}</select>
          <article className="library-lab"><p className="eyebrow">OPTIONAL LOCAL EXERCISE · {labModule.number}</p><h4><TerminalWindow size={23} aria-hidden="true" />{labModule.lab.title}</h4>
            <h5>Before you start</h5><ul>{labModule.lab.prerequisites.map((item) => <li key={item}>{item}</li>)}</ul>
            <h5>Commands</h5><p className="library-command-note">Run from the Java repository root. These commands are shown for your local environment; the browser does not execute them.</p><div className="library-commands">{labModule.lab.commands.map((command, index) => <div key={`${index}-${command}`}><pre tabIndex="0" aria-label={`Local command ${index + 1}`}><code>{command}</code></pre><button type="button" onClick={() => copyCommand(command)} aria-label={`Copy command ${index + 1}`}>Copy</button></div>)}</div>
            <h5>Follow the exercise</h5><ol>{labModule.lab.steps.map((step) => <li key={step}>{step}</li>)}</ol>
            <div className="library-expected"><h5>Expected evidence</h5><p>{labModule.lab.expected}</p></div>
            <button className="text-button" onClick={() => navigate(labModule.id)}>Open this module<ArrowRight size={15} aria-hidden="true" /></button>
          </article>
        </div>}

        {active === 'Bookmarks' && (saved.length ? <div className="library-bookmarks">{saved.map(({ key, module, lesson, step }) => <button key={key} onClick={() => navigate(module.id, step)}><span><small>{module.number} · {module.title}</small><strong>{lesson.title}</strong><span>{lesson.lead}</span></span><ArrowRight size={22} aria-hidden="true" /></button>)}</div> : <EmptyState title="Keep a useful lesson close" text="Use the bookmark control while reading a lesson. Saved lessons appear here, ready to open in Course." />)}

        {active === 'Progress' && <div className="library-progress">
          <div className="library-progress-summary"><div><strong>{stats.count}<span> / {modules.length}</span></strong><span>assessed missions complete</span></div><div><strong>{stats.opened}<span> / {lessonCount}</span></strong><span>lessons opened</span></div></div>
          <progress max={modules.length} value={stats.count} aria-label={`${stats.count} of ${modules.length} assessed missions complete`} />
          <ul className="library-mission-status">{modules.map((module) => {
            const complete = stats.complete.includes(module.id);
            return <li key={module.id}><span className={complete ? 'complete' : ''}>{complete ? <CheckCircle size={21} aria-hidden="true" /> : <span className="library-status-number">{module.number}</span>}<span><strong>{module.title}</strong><small>{module.mission.title}</small></span></span><span className={complete ? 'library-complete-label' : 'library-pending-label'}>{complete ? 'Complete' : 'Not complete'}</span></li>;
          })}</ul>
          <section className="library-progress-actions"><h4>Keep your learning record</h4><p>Export a JSON backup to restore these browser activities later. A learning report is a readable summary; it is not a professional certification or proof of Java or cloud execution.</p><div><button className="secondary-button" onClick={onExport}><DownloadSimple size={17} aria-hidden="true" />Export progress JSON</button><button className="secondary-button" disabled={importing} onClick={() => fileInput.current.click()}><UploadSimple size={17} aria-hidden="true" />{importing ? 'Checking file…' : 'Restore progress JSON'}</button><button className="secondary-button" onClick={onDownloadReport}><DownloadSimple size={17} aria-hidden="true" />Download learning report</button></div><input ref={fileInput} type="file" accept=".json,application/json" className="sr-only" tabIndex={-1} aria-label="Restore progress from a JSON file" onChange={importFile} /></section>
          <section className="library-reset"><div><h4>Start again</h4><p>Reset mission results, lesson history and bookmarks. You can export a backup first.</p></div><button type="button" onClick={onResetAll}>Reset all progress</button></section>
        </div>}

        {active === 'Evidence' && <div className="library-evidence">
          <article><span className="library-evidence-kind">BROWSER LEARNING</span><h4>Executable models, visible evidence</h4><p>The course shares the repository’s canonical purchase and settlement fixtures. Interactive state machines let you classify records, follow a run, test boundaries and recover from a failure. Completing a mission verifies the requested actions in this browser model.</p><p>It does not execute Java, contact MongoDB, upload to S3 or provision AWS. A simulated successful route does not prove a real network rule.</p></article>
          <article><span className="library-evidence-kind">RETAINED LOCAL CHECKPOINT</span><h4>Tests behind the project</h4><p>The repository documents a historical local verification checkpoint. Java unit tests exercise business logic, while acceptance tests use real Spring HTTP, MongoDB and worker code with a version-aware S3 stub. Independent canonical expectations verify the complete result.</p><p>Those documented results are not a fresh test run caused by opening this course. Optional local labs explain how to obtain your own current reports.</p><button className="text-button" onClick={() => viewSource('cloud-testing')}>Read the testing evidence<ArrowRight size={14} aria-hidden="true" /></button></article>
          <article><span className="library-evidence-kind pending">CLOUD OBSERVATIONS PENDING</span><h4>Designed in source; deployment still needs proof</h4><p>The Terraform files describe the intended cloud architecture. Project documentation leaves actual Terraform validation, account eligibility, Atlas startup, AWS upload enforcement, event delivery, private routing, runtime measurements and teardown pending.</p><p>The deployment lesson is a rehearsal. Neither browser completion nor local stub tests clear those observations.</p><button className="text-button" onClick={() => viewSource('cloud-deployment')}>Read the deployment boundary<ArrowRight size={14} aria-hidden="true" /></button></article>
          <article><span className="library-evidence-kind">PROPOSED EXTENSIONS</span><h4>Ideas are labeled as changes</h4><p>A queue or dead-letter queue, larger report storage and new business outcomes are capstone design proposals. The existing S3 notification invokes Lambda directly. There is no queue, NAT gateway, load balancer or direct worker-to-Atlas connection in the source architecture.</p><button className="text-button" onClick={() => navigate('capstone', 2)}>Explore the extension lesson<ArrowRight size={14} aria-hidden="true" /></button></article>
          <button className="secondary-button" onClick={() => chooseSection('Sources')}><Code size={18} aria-hidden="true" />Browse all {sources.length} source files</button>
        </div>}
      </div>
      <footer className="library-footer">Source-backed lessons · Browser-first course · Optional local practice</footer>
    </div>
  </dialog>;
}

function EmptyState({ title, text, onClear }) {
  return <div className="library-empty"><BookOpen size={29} aria-hidden="true" /><h4>{title}</h4><p>{text}</p>{onClear && <button className="text-button" onClick={onClear}>Clear search</button>}</div>;
}
