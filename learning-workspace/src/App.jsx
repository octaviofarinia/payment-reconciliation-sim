import { useEffect, useReducer, useRef, useState } from 'react';
import { ArrowRight, BookOpen, Books, BookmarkSimple, CheckCircle, Code, Compass, List, MagnifyingGlass, X } from './icons.js';
import { references, evidenceFor } from './lesson.js';
import { modules, moduleById } from './content/course.js';
import { modes, storageKey, parseRoute, routeFor } from './course-model.js';
import { createProgress, progressReducer, courseStats, moduleComplete, serializeProgress, restoreProgress, learningReport } from './progress.js';
import { DataExplorer } from './DataExplorer.jsx';
import { CourseView, MissionView } from './LessonViews.jsx';
import { MissionPanel } from './MissionPanel.jsx';
import { SourceDialog, CodeExplorer } from './SourceDialog.jsx';
import { CourseOverview, LessonReader, ContextInspector } from './CourseSite.jsx';
import { SystemExplorer } from './SystemExplorer.jsx';
import { SimulationLab } from './SimulationLab.jsx';
import { SearchDialog } from './SearchDialog.jsx';
import { LibraryDialog } from './LibraryDialog.jsx';

function initialProgress() {
  let state = createProgress();
  let notice = '';
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved) state = restoreProgress(saved);
  } catch { notice = 'Saved progress could not be loaded. A fresh workspace is ready; you can import a backup in the Library.'; }
  if (window.location.hash) {
    const route = parseRoute(window.location.hash);
    if (route) state = progressReducer(state, { type: 'navigate', ...route });
  }
  return { state, notice };
}

function download(name, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function CurriculumRail({ state, navigate, onLibrary }) {
  const stats = courseStats(state);
  return <>
    <button className={`course-overview-link ${state.overview ? 'active' : ''}`} onClick={() => navigate({ overview: true })}><Compass size={19} /> Course overview <ArrowRight size={15} /></button>
    <div className="rail-section-title"><p className="eyebrow">YOUR LEARNING PATH</p><span>8 modules</span></div>
    <nav className="module-navigation" aria-label="Curriculum">{modules.map((module) => <section key={module.id} className={state.moduleId === module.id && !state.overview ? 'module-active' : ''}>
      <button className="module-nav-button" aria-current={!state.overview && state.moduleId === module.id ? 'true' : undefined} onClick={() => navigate({ overview: false, mode: 'Course', moduleId: module.id, step: 0, lens: module.lens })}><span className={`nav-module-number ${moduleComplete(state, module.id) ? 'done' : ''}`}>{moduleComplete(state, module.id) ? <CheckCircle size={18} weight="fill" /> : module.number}</span><span>{module.title}</span></button>
      {state.moduleId === module.id && !state.overview && <ol className="module-lessons">{module.lessons.map((lesson, step) => <li key={lesson.id}><button className={state.step === step && state.mode === 'Course' ? 'active' : ''} aria-current={state.step === step && state.mode === 'Course' ? 'step' : undefined} onClick={() => navigate({ overview: false, mode: 'Course', step })}><span className={`lesson-visit-dot ${state.visited.includes(`${module.id}:${step}`) ? 'visited' : ''}`} /><span>{lesson.title}</span>{state.bookmarks.includes(`${module.id}:${step}`) && <BookmarkSimple size={11} weight="fill" />}</button></li>)}</ol>}
    </section>)}</nav>
    <div className="rail-bottom"><p className="eyebrow">EARNED THROUGH PRACTICE</p><div className="progress-label"><span>{stats.count} of 8 missions complete</span><span>{stats.percent}%</span></div><progress value={stats.count} max="8" aria-label="Overall mission progress" /><button className="quiet-button" onClick={() => onLibrary('Progress')}>Your progress & backups <ArrowRight size={14} /></button><p className="saved-note"><span className="status-dot" /> Saved in this browser</p></div>
  </>;
}

function ConfirmReset({ target, onCancel, onConfirm }) {
  const ref = useRef(null);
  useEffect(() => { if (target && !ref.current.open) ref.current.showModal(); if (!target && ref.current.open) ref.current.close(); }, [target]);
  return <dialog ref={ref} className="confirm-dialog" aria-labelledby="reset-title" onCancel={onCancel} onClose={onCancel}><h2 id="reset-title">{target === 'all' ? 'Start a fresh learning journey?' : 'Reset this mission?'}</h2><p>{target === 'all' ? 'This clears your saved missions, bookmarks, and lesson history from this browser. Export a backup from the Library first if you want to keep them.' : 'This clears the current mission’s attempts and earned completion. Your other modules and bookmarks stay saved.'}</p><div><button className="secondary-button" onClick={onCancel} autoFocus>Keep my progress</button><button className="reset-confirm-button" onClick={onConfirm}>{target === 'all' ? 'Reset all progress' : 'Reset mission'}</button></div></dialog>;
}

export function App() {
  const [initial] = useState(initialProgress);
  const [state, rawDispatch] = useReducer(progressReducer, initial.state);
  const [sourceId, setSourceId] = useState(null);
  const [codeId, setCodeId] = useState(moduleById(state.moduleId).sourceIds[0]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [library, setLibrary] = useState(null);
  const [notice, setNotice] = useState(initial.notice);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState(null);
  const [resetVersion, setResetVersion] = useState(0);
  const drawer = useRef(null);
  const main = useRef(null);
  const route = routeFor(state);
  const previousRoute = useRef(route);
  const module = moduleById(state.moduleId);
  const evidence = evidenceFor(state.selected);

  function navigate(patch) {
    const updated = { overview: false, ...patch };
    if (patch.moduleId && patch.moduleId !== state.moduleId) {
      if (patch.step === undefined) updated.step = 0;
      if (patch.lens === undefined) updated.lens = moduleById(patch.moduleId).lens;
    }
    const next = progressReducer(state, { type: 'navigate', ...updated });
    rawDispatch({ type: 'navigate', ...updated });
    if (window.location.hash !== routeFor(next)) window.location.hash = routeFor(next);
    setDrawerOpen(false);
  }
  function dispatch(action) {
    if (action.type === 'mode') navigate({ mode: action.mode });
    else if (action.type === 'step') navigate({ mode: 'Course', step: action.step });
    else rawDispatch(action);
  }
  const openLibrary = (section = 'Glossary', query = '') => setLibrary({ section, query });

  useEffect(() => {
    try { localStorage.setItem(storageKey, serializeProgress(state)); }
    catch { setNotice('Browser storage is unavailable or full. Export your progress from the Library to keep a backup.'); }
  }, [state]);
  useEffect(() => {
    const onHash = () => { const parsed = parseRoute(window.location.hash); if (parsed) { rawDispatch({ type: 'navigate', ...parsed }); setDrawerOpen(false); } };
    const onKeys = (event) => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setSearchOpen(true); } };
    window.addEventListener('hashchange', onHash); window.addEventListener('keydown', onKeys);
    return () => { window.removeEventListener('hashchange', onHash); window.removeEventListener('keydown', onKeys); };
  }, []);
  useEffect(() => {
    document.title = `${state.overview ? 'From Java to the cloud' : `${module.title} · ${state.mode}`} | Reconciliation Lab`;
    if (previousRoute.current !== route) { main.current?.scrollTo({ top: 0 }); if (window.matchMedia('(max-width: 760px)').matches) window.scrollTo({ top: 0 }); main.current?.focus({ preventScroll: true }); }
    previousRoute.current = route;
  }, [route, state.overview, module.title, state.mode]);
  useEffect(() => { setCodeId(moduleById(state.moduleId).sourceIds[0]); }, [state.moduleId]);
  useEffect(() => { if (drawerOpen && !drawer.current.open) drawer.current.showModal(); if (!drawerOpen && drawer.current.open) drawer.current.close(); }, [drawerOpen]);

  function navigateTabs(event, index) {
    const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (!direction && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + direction + 3) % 3;
    navigate({ mode: modes[next] });
    // Navigation focus moves to content for other controls; tab navigation keeps focus on the active tab.
    requestAnimationFrame(() => document.getElementById(`tab-${modes[next]}`)?.focus());
  }
  async function importProgress(file) {
    if (!file) return;
    try {
      if (file.size > 4_000_000) throw new Error('Choose a progress file under 4 MB.');
      const restored = restoreProgress(await file.text()); rawDispatch({ type: 'replace', state: restored });
      window.location.hash = routeFor(restored); setNotice('Progress restored. Your lessons, missions, and bookmarks are ready.');
    } catch (error) { setNotice(error.message || 'Could not read the progress file. Your current progress has not changed.'); }
  }
  function confirmReset() {
    if (resetTarget === 'all') { rawDispatch({ type: 'reset-all' }); window.location.hash = '#/overview'; setNotice('All progress reset. Your new journey is ready.'); }
    else { rawDispatch({ type: 'reset-module', moduleId: resetTarget }); setNotice('This mission was reset. Other progress is saved.'); }
    setResetVersion((value) => value + 1); setResetTarget(null);
  }

  return <div className="app-shell complete-site">
    <a className="skip-link" href="#workspace-content">Skip to workspace</a>
    <header className="topbar"><button className="wordmark" onClick={() => navigate({ overview: true })}><span className="brand-mark"><span /><span /><span /></span>Reconciliation Lab<span className="brand-dot">.</span></button>
      <nav className="mode-tabs" role="tablist" aria-label="Learning workspace">{modes.map((mode, index) => <button key={mode} id={`tab-${mode}`} role="tab" aria-selected={(state.overview ? 'Course' : state.mode) === mode} aria-controls="workspace-content" tabIndex={(state.overview ? 'Course' : state.mode) === mode ? 0 : -1} className={(state.overview ? 'Course' : state.mode) === mode ? 'active' : ''} onClick={() => navigate({ mode })} onKeyDown={(event) => navigateTabs(event, index)}>{mode}</button>)}</nav>
      <div className="header-tools"><button className="header-search" onClick={() => setSearchOpen(true)} aria-label="Search the course"><MagnifyingGlass size={18} /><span>Search</span><kbd>Ctrl K</kbd></button><button className="header-library" aria-label="Library" onClick={() => openLibrary()}><Books size={19} /><span>Library</span></button><span className="simulation-label"><span className="status-dot" /> Browser lab</span></div>
    </header>
    <div className="mobile-navigation"><button onClick={() => setDrawerOpen(true)}><List size={18} /> {state.overview ? 'Learning path' : `${module.number} · ${module.title}`}</button>{!state.overview && <button onClick={() => navigate({ mode: 'Missions' })}>Mission <ArrowRight size={15} /></button>}</div>
    {notice && <div className="site-notice" role="status"><span>{notice}</span><button aria-label="Dismiss notification" onClick={() => setNotice('')}><X size={16} /></button></div>}
    <div className="workspace"><aside className="course-rail" aria-label="Course navigation"><CurriculumRail state={state} navigate={navigate} onLibrary={openLibrary} /></aside>
      <main ref={main} id="workspace-content" className={`main-workspace mode-${state.overview ? 'overview' : state.mode.toLowerCase()} lens-${state.lens}`} role="tabpanel" aria-labelledby={`tab-${state.overview ? 'Course' : state.mode}`} tabIndex="-1">
        {!state.overview && <div className="context-bar full-context"><span>{module.number} / {module.title}</span><label className="record-select"><span>Shared record</span><select aria-label="Selected shared record" value={state.selected} onChange={(event) => dispatch({ type: 'select', reference: event.target.value })}>{references.map((reference) => <option key={reference}>{reference}</option>)}</select></label></div>}
        {state.overview ? <CourseOverview state={state} navigate={navigate} onLibrary={openLibrary} /> : <>
          {state.mode === 'Course' && (module.id === 'reconciliation' ? <><div className="first-lesson-tools"><button className="quiet-button" aria-pressed={state.bookmarks.includes(`reconciliation:${state.step}`)} onClick={() => dispatch({ type: 'bookmark' })}><BookmarkSimple size={16} weight={state.bookmarks.includes(`reconciliation:${state.step}`) ? 'fill' : 'regular'} /> Save lesson</button><button className="quiet-button" onClick={() => openLibrary('Local labs')}>Optional local lab <ArrowRight size={14} /></button></div><CourseView state={state} dispatch={dispatch} onSource={() => setSourceId('comparator')} /></> : <LessonReader state={state} module={module} navigate={navigate} dispatch={dispatch} onSource={setSourceId} onLibrary={openLibrary} />)}
          {state.mode === 'Explorer' && <><div className="explorer-lenses" aria-label="Explorer lenses">{[['data', 'Data', Compass], ['system', 'System', BookOpen], ['code', 'Code', Code]].map(([id, label, Icon]) => <button key={id} aria-pressed={state.lens === id} className={state.lens === id ? 'active' : ''} onClick={() => navigate({ lens: id })}><Icon size={16} /> {label}</button>)}<span>Same system. A different lens.</span></div>
            {state.lens === 'data' && <><div className="explorer-heading"><div><p className="eyebrow">CANONICAL DATASET · 01 OCT 2026</p><span className="selected-reference">{state.selected}</span></div><span className="interaction-note">Select a row to follow its evidence</span></div><DataExplorer selected={state.selected} onSelect={(reference) => dispatch({ type: 'select', reference })} completed={state.completed} /><footer className="explorer-footer"><span>{evidence.rows.length > 1 ? 'One reference. Two settlement rows.' : !evidence.purchase ? 'A settlement without an internal purchase.' : !evidence.rows.length ? 'A purchase without a settlement row.' : evidence.purchase.amountCentavos === evidence.rows[0].amountCentavos ? 'Same reference. Same amount.' : 'Same reference. Different amount.'}</span><button className="text-button" onClick={() => setSourceId('comparator')}><Code size={19} /> View comparison code</button></footer></>}
            {state.lens === 'system' && <SystemExplorer module={module} selected={state.selected} onSource={setSourceId} />}
            {state.lens === 'code' && <CodeExplorer module={module} selected={state.selected} sourceId={codeId} onSelectSource={setCodeId} onSource={setSourceId} />}
          </>}
          {state.mode === 'Missions' && (module.id === 'reconciliation' ? <MissionView state={state} dispatch={dispatch} onSource={() => setSourceId('comparator')} /> : <SimulationLab key={`${module.id}-${resetVersion}`} moduleId={module.id} lab={state.labs[module.id]} onAction={(action) => action.type === 'reset' ? setResetTarget(module.id) : dispatch({ type: 'lab', moduleId: module.id, action })} selected={state.selected} onSelect={(reference) => dispatch({ type: 'select', reference })} />)}
        </>}
      </main>
      {!state.overview && module.id === 'reconciliation' ? <MissionPanel key={`${resetVersion}-${state.selected}`} state={state} dispatch={dispatch} onReset={() => setResetTarget('reconciliation')} onContinue={() => navigate({ moduleId: 'api', step: 0, mode: 'Course', lens: 'code' })} /> : <ContextInspector state={state} module={module} navigate={navigate} onSource={setSourceId} onReset={() => setResetTarget(module.id)} onLibrary={openLibrary} />}
    </div>
    <dialog ref={drawer} className="curriculum-drawer" aria-label="Learning path" onCancel={() => setDrawerOpen(false)} onClose={() => setDrawerOpen(false)}><div className="drawer-heading"><strong>Learning path</strong><button className="icon-button" aria-label="Close learning path" onClick={() => setDrawerOpen(false)}><X size={20} /></button></div><CurriculumRail state={state} navigate={navigate} onLibrary={(section) => { setDrawerOpen(false); openLibrary(section); }} /></dialog>
    <SourceDialog open={Boolean(sourceId)} sourceId={sourceId || 'comparator'} selected={state.selected} onClose={() => setSourceId(null)} />
    <SearchDialog open={searchOpen} onClose={() => setSearchOpen(false)} onNavigate={navigate} onSource={setSourceId} onGlossary={(term) => openLibrary('Glossary', term)} />
    <LibraryDialog open={Boolean(library)} section={library?.section || 'Glossary'} query={library?.query || ''} onClose={() => setLibrary(null)} state={state} onNavigate={(patch) => { setLibrary(null); navigate(patch); }} onSource={setSourceId} onExport={() => { download('reconciliation-lab-progress.json', serializeProgress(state), 'application/json'); setNotice('Progress backup downloaded. You can restore it in the Library.'); }} onImport={importProgress} onResetAll={() => { setLibrary(null); setResetTarget('all'); }} onDownloadReport={() => download('reconciliation-lab-learning-report.md', learningReport(state, modules), 'text/markdown')} notice={notice} />
    <ConfirmReset target={resetTarget} onCancel={() => setResetTarget(null)} onConfirm={confirmReset} />
  </div>;
}
