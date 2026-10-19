import { useEffect, useRef } from 'react';
import { ArrowRight, ArrowLeft, CheckCircle, Code, MagnifyingGlass, ListChecks, BookOpen } from './icons.js';
import { evidenceFor, references, outcomes, purchases, settlement } from './lesson.js';
import { explanations, money } from './presentation.js';

export const lessonSteps = [
  { title: 'Compare purchases and settlement.', short: 'Compare amounts' },
  { title: 'Classify each reference.', short: 'Classify the result' },
  { title: 'Read the daily report.', short: 'Read the daily report' },
];

function SelectedEvidence({ selected }) {
  const { purchase, rows } = evidenceFor(selected);
  return <div className="selected-evidence"><section><p className="eyebrow">OUR PURCHASE</p><strong>{purchase ? `ARS ${money(purchase.amountCentavos)}` : 'No purchase'}</strong><span>{selected}</span></section><section><p className="eyebrow">SETTLEMENT EVIDENCE</p><strong>{rows.length ? `ARS ${money(rows[0].amountCentavos)}` : 'No row'}</strong><span>{rows.length > 1 ? `${rows.length} rows · ${rows.map((row) => row.rowNumber).join(', ')}` : rows.length ? `Row ${rows[0].rowNumber} · ${selected}` : selected}</span></section></div>;
}

export function CourseView({ state, dispatch, onSource }) {
  const complete = Object.keys(state.completed).length === 5;
  const heading = useRef(null);
  const previousStep = useRef(state.step);
  useEffect(() => {
    if (previousStep.current !== state.step) {
      heading.current.focus({ preventScroll: true });
      heading.current.scrollIntoView({ block: 'start' });
    }
    previousStep.current = state.step;
  }, [state.step]);
  return <article className="course-content">
    <div className="content-kicker"><BookOpen size={17} /><span>THE FIRST RECONCILIATION</span><span className="muted">{state.step + 1} / 3</span></div>
    <h1 ref={heading} tabIndex="-1">{lessonSteps[state.step].title}</h1>
    {state.step === 0 && <>
      <p className="lead">After our system recorded the purchases, the processor sent a settlement file for the same day.</p>
      <p>Start with the <strong>transaction reference</strong>. It identifies a purchase across both datasets. Find the same reference, then compare the amounts.</p>
      <div className="lesson-selected"><span className="eyebrow">EXPLORING TOGETHER</span><span className="selected-reference">{state.selected}</span></div>
      <SelectedEvidence selected={state.selected} />
      <p className="small muted">Amounts are displayed in ARS. The code stores integer centavos: ARS 400.00 is 40,000 centavos.</p>
      <div className="takeaway"><strong>Try it yourself</strong><p>Open the Explorer and select a row. Your selected reference follows you back here and into the mission.</p><button className="text-button" onClick={() => dispatch({ type: 'mode', mode: 'Explorer' })}>Explore this record <ArrowRight size={17} /></button></div>
    </>}
    {state.step === 1 && <>
      <p className="lead">Every distinct reference receives one outcome based on the records present or missing in each dataset.</p>
      <div className="rule-list">{outcomes.map(({ value, label }) => <section key={value}><h3>{label}</h3><p>{explanations[value]}</p></section>)}</div>
      <div className="takeaway"><strong>Order matters</strong><p>Check for duplicate settlement rows first. Two identical amounts still represent two rows, so the outcome is Duplicate.</p><button className="text-button" onClick={onSource}><Code size={18} /> See the Java rule</button></div>
    </>}
    {state.step === 2 && <>
      <p className="lead">A reconciliation explains the whole day. Every result connects a reference to the records that justify its classification.</p>
      <div className="day-counts"><div><strong>{purchases.length}</strong><span>internal purchases</span></div><div><strong>{settlement.length}</strong><span>settlement rows</span></div><div><strong>{references.length}</strong><span>distinct references</span></div></div>
      <h2>Count references, not rows.</h2><p>One reference can appear in both datasets, and a duplicate has multiple evidence rows. We compare the union of references and publish one result for each.</p>
      <div className="takeaway"><strong>{complete ? 'You explained every reference.' : 'Put the rules into practice.'}</strong><p>{complete ? 'You found a match, two kinds of missing record, a different amount, and a duplicate. The same pure comparison rules are implemented in the Java worker.' : 'Classify all five references in Missions. Each correct prediction builds the evidence for a complete report.'}</p><button className="text-button" onClick={() => dispatch({ type: 'mode', mode: 'Missions' })}>{complete ? 'Review your mission' : 'Start the mission'} <ArrowRight size={17} /></button></div>
      <button className="text-button" onClick={onSource}><Code size={18} /> Connect this lesson to the code</button>
    </>}
    <nav className="lesson-pagination" aria-label="Lesson steps"><button className="quiet-button" disabled={state.step === 0} onClick={() => dispatch({ type: 'step', step: state.step - 1 })}><ArrowLeft size={16} /> Previous</button><button className="secondary-button" onClick={() => state.step < 2 ? dispatch({ type: 'step', step: state.step + 1 }) : dispatch({ type: 'mode', mode: 'Missions' })}>{state.step < 2 ? 'Continue lesson' : 'Open Missions'} <ArrowRight size={16} /></button></nav>
  </article>;
}

export function MissionView({ state, dispatch, onSource }) {
  const count = Object.keys(state.completed).length;
  return <section className="mission-content">
    <div className="content-kicker"><ListChecks size={18} /><span>MISSION 01</span><span className="muted">{count} / 5 complete</span></div>
    <h1>Reconcile the day.</h1><p className="lead">Inspect the evidence for each of the five references, choose an outcome, and check your prediction.</p>
    <div className="case-list" aria-label="Mission references">{references.map((reference) => <button key={reference} className={`case-item ${state.selected === reference ? 'active' : ''}`} aria-pressed={state.selected === reference} onClick={() => dispatch({ type: 'select', reference })}><span>{reference}</span><span>{state.completed[reference] ? <><CheckCircle size={17} weight="fill" /> Classified</> : state.selected === reference ? <>Investigating <ArrowRight size={16} /></> : 'To investigate'}</span></button>)}</div>
    <div className="mission-evidence-heading"><div><p className="eyebrow">CURRENT CASE</p><h2>{state.selected}</h2></div><button className="text-button" onClick={() => dispatch({ type: 'mode', mode: 'Explorer' })}><MagnifyingGlass size={17} /> Inspect in Explorer</button></div>
    <SelectedEvidence selected={state.selected} />
    <div className="mission-guide"><p>Use the classification panel to test your answer. A wrong prediction is a chance to investigate; it won’t advance your progress.</p><button className="text-button" onClick={onSource}><Code size={18} /> View comparison code</button></div>
    {count === 5 && <div className="mission-complete"><CheckCircle size={28} weight="fill" /><div><h2>All five references explained.</h2><p>You’ve completed the first reconciliation mission.</p></div></div>}
  </section>;
}
