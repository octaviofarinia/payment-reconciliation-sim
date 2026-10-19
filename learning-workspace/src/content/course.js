import { backendModules, backendSources, backendGlossary } from './backend.js';
import { cloudModules, cloudSources, cloudGlossary } from './cloud.js';
import { labSpecs } from '../lab-specs.js';
import comparator from '../reference/ReconciliationComparator.java?raw';

const reconciliation = {
  id: 'reconciliation', number: '01', title: 'Reconcile the day', duration: '20 min', lens: 'data',
  summary: 'Compare purchases with settlement records and classify all five references.',
  outcome: 'Explain every canonical reference using the original purchase and settlement evidence.',
  sourceIds: ['comparator'],
  lessons: [
    { id: 'reconciliation-reference', title: 'Compare purchases and settlement', kicker: 'THE FIRST RECONCILIATION', lead: 'Join the internal ledger and the processor’s settlement by reference, then compare integer centavos.', paragraphs: ['A reference connects two independently produced records. Missing evidence is a meaningful result.'], takeaway: { title: 'Follow one reference', text: 'The selected reference follows you between Course, Explorer, and Missions.' }, concepts: ['reference', 'centavos'], sourceIds: ['comparator'] },
    { id: 'reconciliation-outcomes', title: 'Classify each reference', kicker: 'THE FIVE OUTCOMES', lead: 'Classify each reference in a deliberate order: duplicate evidence first, then missing records, then amounts.', paragraphs: ['Repeated rows are retained as evidence. Two matching amounts do not make duplicate rows disappear.'], takeaway: { title: 'Order matters', text: 'DUPLICATE wins even when both settlement rows match the purchase.' }, concepts: ['reconciliation', 'duplicate'], sourceIds: ['comparator'] },
    { id: 'reconciliation-report', title: 'Read the daily report', kicker: 'ONE COMPLETE REPORT', lead: 'Four purchases and five settlement rows produce five results: one result per distinct reference across both datasets.', paragraphs: ['The union includes references that appear on only one side. The worker sorts references deterministically.'], takeaway: { title: 'Count references, not rows', text: 'The canonical report contains exactly one of each outcome.' }, concepts: ['union', 'pure-function'], sourceIds: ['comparator'] },
  ],
  mission: { title: 'Reconcile the day', brief: 'Inspect all five references and classify the evidence.', goal: 'Correctly classify MATCH-001, MISS-001, AMOUNT-001, EXT-001, and DUP-001.' },
  lab: { title: 'Run the pure comparator tests', prerequisites: ['Java 21', 'Repository checkout and Maven wrapper; no AWS account needed'], commands: ['./mvnw -pl reconciliation-worker -am test'], steps: ['Read ReconciliationComparator.java and its tests.', 'Trace the union of references before the classification branch.', 'Add a test for duplicate settlement rows with no internal purchase.'], expected: 'Worker unit tests pass. The additional case produces DUPLICATE and retains both evidence rows.' },
};

const rawModules = [reconciliation, ...backendModules, ...cloudModules];
export const modules = rawModules.map(module => {
  const spec = labSpecs[module.id];
  return spec ? { ...module, mission: { title: spec.title, brief: spec.intro, goal: `${spec.tasks.map(task => task.label).join('; ')}.` } } : module;
});
export const sources = [{ id: 'comparator', title: 'ReconciliationComparator.java', path: 'reconciliation-worker/src/main/java/org/octavio/paymentreconciliationsim/worker/domain/ReconciliationComparator.java', group: 'Worker', note: 'The pure domain rule behind the canonical dataset. Duplicate evidence is checked before missing records and amount comparison.', focus: [11, 57], content: comparator }, ...backendSources, ...cloudSources];
export const glossary = [
  { id: 'reference', term: 'Transaction reference', definition: 'The stable identifier used to join an internal purchase with every corresponding settlement row. Row position is not identity.', moduleId: 'reconciliation' },
  { id: 'centavos', term: 'Integer centavos', definition: 'Money represented in the smallest currency unit. ARS 400.00 is 40,000 centavos, avoiding floating-point comparison.', moduleId: 'reconciliation' },
  { id: 'reconciliation', term: 'Reconciliation', definition: 'Explaining differences between two independently recorded views of the same business activity.', moduleId: 'reconciliation' },
  { id: 'duplicate', term: 'Duplicate settlement evidence', definition: 'More than one settlement row for a reference. This business outcome is different from a repeated infrastructure event.', moduleId: 'reconciliation' },
  { id: 'union', term: 'Union of references', definition: 'Every distinct reference present in either dataset. It includes purchases absent from settlement and settlement rows absent internally.', moduleId: 'reconciliation' },
  { id: 'pure-function', term: 'Pure comparison', definition: 'A calculation whose result depends on its inputs and which performs no network or database work. The worker comparator has this boundary.', moduleId: 'reconciliation' },
  ...backendGlossary, ...cloudGlossary,
];

export function moduleById(id) { return modules.find((module) => module.id === id) || reconciliation; }
export function sourceById(id) { return sources.find((source) => source.id === id) || sources[0]; }
export function buildSearchIndex() {
  return [
    ...modules.flatMap((module) => module.lessons.map((lesson, step) => ({ id: lesson.id, type: 'Lesson', title: lesson.title, detail: `${module.number} · ${module.title}`, text: [lesson.lead, ...lesson.paragraphs].join(' '), moduleId: module.id, step }))),
    ...modules.map((module) => ({ id: `mission-${module.id}`, type: 'Mission', title: module.mission.title, detail: module.mission.brief, text: module.mission.goal, moduleId: module.id })),
    ...glossary.map((term) => ({ ...term, type: 'Glossary', title: term.term, detail: term.definition, text: term.definition })),
    ...sources.map((source) => ({ id: source.id, type: 'Source', title: source.title, detail: source.path, text: source.note })),
  ];
}
