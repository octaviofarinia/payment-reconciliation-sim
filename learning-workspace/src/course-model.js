export const moduleIds = Object.freeze(['reconciliation', 'api', 'stability', 'upload', 'worker', 'security', 'recovery', 'capstone']);
export const modes = Object.freeze(['Course', 'Explorer', 'Missions']);
export const lenses = Object.freeze(['data', 'system', 'code']);
export const progressVersion = 2;
export const storageKey = 'reconciliation-lab:v2';

export function parseRoute(hash) {
  const parts = hash.replace(/^#\/?/, '').split('/');
  if (!parts[0] || parts[0] === 'overview') return { overview: true };
  const mode = modes.find((value) => value.toLowerCase() === parts[0]);
  if (!mode || !moduleIds.includes(parts[1])) return null;
  const route = { overview: false, mode, moduleId: parts[1] };
  if (mode === 'Course') route.step = Math.max(0, Math.min(2, Number(parts[2] || 1) - 1)) || 0;
  if (mode === 'Explorer') route.lens = lenses.includes(parts[2]) ? parts[2] : 'data';
  return route;
}

export function routeFor(state) {
  if (state.overview) return '#/overview';
  const suffix = state.mode === 'Course' ? `/${state.step + 1}` : state.mode === 'Explorer' ? `/${state.lens}` : '';
  return `#/${state.mode.toLowerCase()}/${state.moduleId}${suffix}`;
}
