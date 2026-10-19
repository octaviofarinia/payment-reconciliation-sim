import { initialState, lessonReducer, references, outcomes, classify } from './lesson.js';
import { createLab, transitionLab, isLabComplete } from './simulations.js';
import { moduleIds, modes, lenses, progressVersion } from './course-model.js';

const lessonKey = (moduleId, step) => `${moduleId}:${step}`;
const validKeys = new Set(moduleIds.flatMap((id) => [0, 1, 2].map((step) => lessonKey(id, step))));
const knownOutcomes = new Set(outcomes.map(({ value }) => value));

export function createProgress() {
  return { ...initialState, mode: 'Course', overview: true, moduleId: 'reconciliation', lens: 'data',
    visited: [], bookmarks: [], resume: { moduleId: 'reconciliation', step: 0 },
    labs: Object.fromEntries(moduleIds.slice(1).map((id) => [id, createLab(id)])),
    labEvents: Object.fromEntries(moduleIds.slice(1).map((id) => [id, []])),
  };
}

export function progressReducer(state, action) {
  switch (action.type) {
    case 'navigate': {
      const next = { ...state };
      if (typeof action.overview === 'boolean') next.overview = action.overview;
      if (moduleIds.includes(action.moduleId)) next.moduleId = action.moduleId;
      if (modes.includes(action.mode)) next.mode = action.mode;
      if (lenses.includes(action.lens)) next.lens = action.lens;
      if ([0, 1, 2].includes(action.step)) next.step = action.step;
      if (!next.overview && next.mode === 'Course') {
        next.visited = [...new Set([...next.visited, lessonKey(next.moduleId, next.step)])];
        next.resume = { moduleId: next.moduleId, step: next.step };
      }
      return next;
    }
    case 'lab': {
      if (!moduleIds.slice(1).includes(action.moduleId)) return state;
      const id = action.moduleId;
      if (action.action.type === 'reset') return progressReducer(state, { type: 'reset-module', moduleId: id });
      let events = state.labEvents[id];
      // Editing a control is not an assessed step. Keep the latest value for each
      // field since the last command, rather than one full CSV per keystroke.
      if (action.action.type === 'configure') {
        const lastCommand = events.findLastIndex((event) => event.type !== 'configure');
        events = events.filter((event, index) => index <= lastCommand || event.key !== action.action.key);
      }
      // Keep the complete action history: restoration replays assessed actions instead of trusting completion flags.
      if (events.length >= 4000) return { ...state, labs: { ...state.labs, [id]: { ...state.labs[id], feedback: { tone: 'warning', message: 'This mission has reached its saved-history limit. Export your progress, then reset this mission to keep experimenting. Other missions are unaffected.' } } } };
      const histories = { ...state.labEvents, [id]: [...events, action.action] };
      if (JSON.stringify(histories).length > 3_800_000) return { ...state, labs: { ...state.labs, [id]: { ...state.labs[id], feedback: { tone: 'warning', message: 'Your saved lab history is nearly full. This action was not applied. Export a backup, then reset a mission to make room for more experiments.' } } } };
      return { ...state, labs: { ...state.labs, [id]: transitionLab(id, state.labs[id], action.action) },
        labEvents: histories };
    }
    case 'bookmark': {
      const key = lessonKey(state.moduleId, state.step);
      return { ...state, bookmarks: state.bookmarks.includes(key) ? state.bookmarks.filter((item) => item !== key) : [...state.bookmarks, key] };
    }
    case 'reset-module': {
      const id = action.moduleId || state.moduleId;
      if (id === 'reconciliation') return { ...state, predictions: {}, completed: {}, feedback: null };
      if (!moduleIds.includes(id)) return state;
      return { ...state, labs: { ...state.labs, [id]: createLab(id) }, labEvents: { ...state.labEvents, [id]: [] } };
    }
    case 'replace': return action.state;
    case 'reset-all': return createProgress();
    case 'mode': return progressReducer(state, { type: 'navigate', overview: false, mode: action.mode });
    case 'step': return progressReducer(state, { type: 'navigate', overview: false, mode: 'Course', step: action.step });
    case 'select': return references.includes(action.reference) ? lessonReducer(state, action) : state;
    case 'predict': return knownOutcomes.has(action.value) ? lessonReducer(state, action) : state;
    case 'check': case 'next': return lessonReducer(state, action);
    default: return state;
  }
}

export function moduleComplete(state, id) {
  return id === 'reconciliation' ? references.every((ref) => state.completed[ref]) : isLabComplete(id, state.labs[id]);
}

export function courseStats(state) {
  const complete = moduleIds.filter((id) => moduleComplete(state, id));
  return { complete, count: complete.length, percent: Math.round(complete.length / moduleIds.length * 100), opened: state.visited.length };
}

export function serializeProgress(state) {
  const { overview, mode, moduleId, step, lens, selected, predictions, completed, visited, bookmarks, resume, labEvents } = state;
  return JSON.stringify({ version: progressVersion, overview, mode, moduleId, step, lens, selected, predictions, completed, visited, bookmarks, resume, labEvents });
}

export function restoreProgress(raw) {
  if (typeof raw !== 'string' || raw.length > 4_000_000) throw new Error('Choose a Reconciliation Lab progress file under 4 MB.');
  let saved;
  try { saved = JSON.parse(raw); } catch { throw new Error('This file is not valid JSON. Your current progress has not changed.'); }
  if (!saved || saved.version !== progressVersion || !saved.labEvents || typeof saved.labEvents !== 'object') throw new Error('This progress file uses an unsupported format. Your current progress has not changed.');
  let state = createProgress();
  state = progressReducer(state, { type: 'navigate', ...Object.fromEntries(['overview', 'mode', 'moduleId', 'step', 'lens'].map((key) => [key, saved[key]])) });
  state.selected = references.includes(saved.selected) ? saved.selected : initialState.selected;
  state.predictions = Object.fromEntries(references.filter((ref) => knownOutcomes.has(saved.predictions?.[ref])).map((ref) => [ref, saved.predictions[ref]]));
  state.completed = Object.fromEntries(references.filter((ref) => saved.completed?.[ref] === true).map((ref) => [ref, true]));
  state.visited = [...new Set((Array.isArray(saved.visited) ? saved.visited : []).filter((key) => validKeys.has(key)))];
  state.bookmarks = [...new Set((Array.isArray(saved.bookmarks) ? saved.bookmarks : []).filter((key) => validKeys.has(key)))];
  state.resume = moduleIds.includes(saved.resume?.moduleId) && [0, 1, 2].includes(saved.resume?.step) ? saved.resume : { moduleId: state.moduleId, step: state.step };
  for (const id of moduleIds.slice(1)) {
    const events = saved.labEvents[id] || [];
    if (!Array.isArray(events) || events.length > 4000) throw new Error('This progress file contains an invalid lab history.');
    for (const action of events) {
      if (!action || typeof action !== 'object' || typeof action.type !== 'string' || JSON.stringify(action).length > 80000) throw new Error('This progress file contains an invalid lab action.');
      // JSON escaping and action metadata add bytes beyond the editor's character limit.
      if (action.type === 'configure' && action.key === 'csv' && (typeof action.value !== 'string' || action.value.length > 12000)) throw new Error('This progress file contains an invalid CSV edit.');
      try { state = progressReducer(state, { type: 'lab', moduleId: id, action }); }
      catch { throw new Error('This progress file contains a lab action that cannot be restored.'); }
    }
  }
  return state;
}

export function learningReport(state, modules) {
  const stats = courseStats(state);
  return [
    '# Reconciliation Lab: learning report', '',
    `${stats.count} of 8 assessed missions complete · ${stats.opened} of 24 lessons opened.`,
    `Selected canonical reference: ${state.selected}.`, '',
    ...modules.map((module) => `${moduleComplete(state, module.id) ? '[complete]' : '[in progress]'} ${module.number} ${module.title}\n  Outcome: ${module.outcome}`), '',
    'This records browser learning activities. It is not proof of Java execution, a cloud deployment, or professional certification.',
    'The optional local labs and project source are available in the site Library.', '',
  ].join('\n');
}
