import { ArrowRight, ArrowLeft, ArrowUpRight, BookOpen, BookmarkSimple, CheckCircle, Code, Compass, ListChecks, Trophy } from './icons.js';
import { modules, glossary, sourceById, moduleById } from './content/course.js';
import { courseStats, moduleComplete } from './progress.js';
import { evidenceFor } from './lesson.js';
import { money } from './presentation.js';

export function CourseOverview({ state, navigate, onLibrary }) {
  const stats = courseStats(state);
  const resume = moduleById(state.resume.moduleId);
  const started = state.visited.length > 0 || stats.count > 0;
  return <div className="overview-content">
    <div className="content-kicker"><Compass size={18} /> A SYSTEM YOU CAN TAKE APART</div>
    <h1>Understand the system.<br /><span>Then change it.</span></h1>
    <p className="lead">Follow a payment from a Java method to the cloud. Build the mental model, inspect the real code, and learn by making the system work — and fail.</p>
    <div className="overview-facts"><span><strong>08</strong> modules</span><span><strong>24</strong> lessons</span><span><strong>08</strong> hands-on missions</span></div>
    <div className="overview-actions"><button className="primary-button" onClick={() => navigate({ overview: false, mode: 'Course', moduleId: resume.id, step: state.resume.step, lens: resume.lens })}>{stats.count === 8 ? 'Revisit your course' : started ? 'Continue learning' : 'Begin the journey'} <ArrowRight size={18} /></button><span>{started ? `${resume.number} · ${resume.lessons[state.resume.step].title}` : 'Basic Java & Spring knowledge is enough.'}</span></div>
    {stats.count === 8 && <div className="mission-complete"><Trophy size={30} /><div><h2>You can explain the whole system.</h2><p>All eight missions complete. Your learning report is ready in the Library.</p><button className="text-button" onClick={() => onLibrary('Progress')}>Get your learning report <ArrowRight size={16} /></button></div></div>}
    <div className="section-heading"><div><p className="eyebrow">THE LEARNING PATH</p><h2>From one record to the whole system.</h2></div><span className="small muted">Explore in any order</span></div>
    <div className="module-grid">{modules.map((module) => {
      const done = moduleComplete(state, module.id);
      const opened = state.visited.filter((key) => key.startsWith(`${module.id}:`)).length;
      return <button key={module.id} className={`module-card ${done ? 'done' : ''}`} onClick={() => navigate({ overview: false, mode: 'Course', moduleId: module.id, step: 0, lens: module.lens })}>
        <div className="module-card-meta"><span className="module-number">{module.number}</span><span>{module.duration} · 3 lessons</span>{done ? <CheckCircle size={20} weight="fill" /> : <ArrowUpRight size={19} />}</div>
        <h3>{module.title}</h3><p>{module.summary}</p><div className="module-card-foot"><span>{done ? 'Mission complete' : opened ? `${opened}/3 lessons opened` : 'Ready to explore'}</span><span className="module-track">{[0, 1, 2].map((step) => <i key={step} className={state.visited.includes(`${module.id}:${step}`) ? 'opened' : ''} />)}<i className={done ? 'earned' : ''} /></span></div>
      </button>;
    })}</div>
    <div className="course-promise"><BookOpen size={24} /><div><h3>Learn here. Go deeper locally.</h3><p>The entire core course runs in your browser. Every module includes an optional lab against the actual repository.</p><button className="text-button" onClick={() => onLibrary('Local labs')}>Explore local labs <ArrowRight size={16} /></button></div></div>
  </div>;
}

const models = {
  api: ['HTTP request', 'Controller', 'Service', 'Repository'],
  stability: ['OPEN day', 'Immutable purchases', 'CLOSE guard', 'Stable input'],
  upload: ['Register metadata', 'Signed PUT', 'S3 version', 'Object event'],
  worker: ['Validate all rows', 'Read purchases', 'Pure compare', 'Publish report'],
  security: ['Network route', 'Security group', 'IAM permission', 'Application role'],
  recovery: ['Retained version', 'New attempt', 'Atomic publish', 'Same report'],
  capstone: ['Form a hypothesis', 'Inspect evidence', 'Recover safely', 'Prove the result'],
};

export function LessonReader({ state, module, navigate, dispatch, onSource, onLibrary }) {
  const lesson = module.lessons[state.step];
  const bookmarked = state.bookmarks.includes(`${module.id}:${state.step}`);
  return <article className="course-content full-lesson">
    <div className="content-kicker"><BookOpen size={17} /><span>{lesson.kicker}</span><span className="muted">{state.step + 1} / 3</span></div>
    <h1>{lesson.title}</h1><p className="lead">{lesson.lead}</p>
    <div className="concept-flow" aria-label="Concept model">{models[module.id].map((label, index) => <div key={label}><span className={index === Math.min(state.step, 3) ? 'focused' : ''}>{label}</span>{index < 3 && <ArrowRight size={15} />}</div>)}</div>
    {lesson.paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    <div className="takeaway"><strong>{lesson.takeaway.title}</strong><p>{lesson.takeaway.text}</p><button className="text-button" onClick={() => navigate({ overview: false, mode: 'Explorer', lens: module.lens })}>Explore how it fits <ArrowRight size={17} /></button></div>
    {lesson.concepts?.length > 0 && <section className="primer-section" aria-label="Technology primers"><p className="eyebrow">A QUICK MENTAL MODEL</p>{lesson.concepts.map((id) => {
      const term = glossary.find((item) => item.id === id);
      return term ? <details key={id}><summary>{term.term}</summary><p>{term.definition}</p></details> : null;
    })}</section>}
    <section className="source-links"><div className="section-heading"><p className="eyebrow">CONNECT IT TO THE CODE</p><span className="small muted">Exact project source</span></div>{lesson.sourceIds.map((id) => {
      const source = sourceById(id);
      return <button key={id} onClick={() => onSource(id)}><Code size={19} /><span><strong>{source.title}</strong><small>{source.note}</small></span><ArrowUpRight size={17} /></button>;
    })}</section>
    <div className="lesson-tools"><button className="quiet-button" aria-pressed={bookmarked} onClick={() => dispatch({ type: 'bookmark' })}><BookmarkSimple size={17} weight={bookmarked ? 'fill' : 'regular'} /> {bookmarked ? 'Bookmarked' : 'Save this lesson'}</button><button className="quiet-button" onClick={() => onLibrary('Local labs')}>Optional local lab <ArrowUpRight size={15} /></button></div>
    <nav className="lesson-pagination" aria-label="Lesson steps"><button className="quiet-button" disabled={state.step === 0} onClick={() => navigate({ step: state.step - 1 })}><ArrowLeft size={16} /> Previous</button><button className="secondary-button" onClick={() => state.step < 2 ? navigate({ step: state.step + 1 }) : navigate({ mode: 'Missions' })}>{state.step < 2 ? 'Continue lesson' : 'Put it into practice'} <ArrowRight size={16} /></button></nav>
  </article>;
}

export function ContextInspector({ state, module, navigate, onSource, onReset, onLibrary }) {
  const evidence = evidenceFor(state.selected);
  const complete = moduleComplete(state, module.id);
  const stats = courseStats(state);
  const next = modules[modules.indexOf(module) + 1];
  return <aside className="mission-panel context-inspector" aria-label="Shared record and mission">
    <p className="eyebrow">{state.overview ? 'YOUR JOURNEY' : 'CONNECTED WORKSPACE'}</p>
    {state.overview ? <><h2 className="inspector-heading">One system.<br />Eight perspectives.</h2><p className="inspector-intro">Every lesson adds a layer. Every mission gives you evidence that you understand it.</p><div className="journey-progress"><strong>{stats.percent}<span>%</span></strong><p>{stats.count} of 8 missions complete</p><progress value={stats.count} max="8" aria-label="Course completion" /></div><div className="how-to-learn"><h3>Make the connections</h3><p><BookOpen size={17} /><span><strong>Course</strong> builds your mental model.</span></p><p><Compass size={17} /><span><strong>Explorer</strong> connects data, system, and code.</span></p><p><ListChecks size={17} /><span><strong>Missions</strong> put your understanding to work.</span></p></div></> : <><h2 className="inspector-heading">{module.mission.title}</h2><p className="inspector-intro">{module.mission.brief}</p><div className={`mission-state ${complete ? 'earned' : ''}`}>{complete ? <CheckCircle size={18} weight="fill" /> : <ListChecks size={18} />}<span>{complete ? 'Mission complete' : 'Mission in progress'}</span></div><div className="inspector-goal"><p className="eyebrow">YOUR OBJECTIVE</p><p>{module.mission.goal}</p></div>{state.mode !== 'Missions' && <button className="primary-button" onClick={() => navigate({ mode: 'Missions' })}>Open the mission <ArrowRight size={17} /></button>}{complete && next && <button className="secondary-button next-module" onClick={() => navigate({ moduleId: next.id, step: 0, mode: 'Course', lens: next.lens })}>Next: {next.title} <ArrowRight size={16} /></button>}{complete && !next && <button className="secondary-button next-module" onClick={() => onLibrary('Progress')}>View learning report <Trophy size={17} /></button>}</>}
    <section className="shared-record-card"><div className="section-heading"><p className="eyebrow">SHARED RECORD</p><span className="small muted">Canonical</span></div><strong className="record-name">{state.selected}</strong><dl><div><dt>Purchase</dt><dd>{evidence.purchase ? `ARS ${money(evidence.purchase.amountCentavos)}` : 'Absent'}</dd></div><div><dt>Settlement</dt><dd>{evidence.rows.length} {evidence.rows.length === 1 ? 'row' : 'rows'}</dd></div>{evidence.rows.map((row) => <div key={row.rowNumber}><dt>Row {row.rowNumber}</dt><dd>ARS {money(row.amountCentavos)}</dd></div>)}</dl><button className="text-button" onClick={() => navigate({ overview: false, mode: 'Explorer', lens: 'data' })}>Follow the evidence <ArrowRight size={15} /></button></section>
    <div className="evidence-note"><span className="status-dot" /><div><strong>Browser simulation</strong><p>Uses the project’s canonical fixtures. Java execution and cloud deployment are separate verification steps.</p><button className="text-button" onClick={() => onLibrary('Evidence')}>About this environment <ArrowUpRight size={13} /></button></div></div>
    {!state.overview && <div className="inspector-footer"><button className="quiet-button" onClick={() => onSource(module.sourceIds[0])}><Code size={16} /> Inspect relevant source</button><button className="quiet-button" onClick={onReset}>Reset this mission</button></div>}
  </aside>;
}
