import canonicalPurchases from './data/canonical-purchases.json' with { type: 'json' };
import canonicalSettlement from './data/canonical-settlement.json' with { type: 'json' };

export const purchases = Object.freeze(canonicalPurchases.map(({ transactionReference, amountCentavos }) =>
  Object.freeze({ reference: transactionReference, amountCentavos })));
export const settlement = Object.freeze(canonicalSettlement.map(({ transactionReference, amountCentavos, rowNumber }) =>
  Object.freeze({ reference: transactionReference, amountCentavos, rowNumber })));
export const references = Object.freeze([...new Set([...purchases, ...settlement].map(({ reference }) => reference))]);
export const outcomes = Object.freeze([
  { value: 'MATCHED', label: 'Matched' },
  { value: 'MISSING_IN_SETTLEMENT', label: 'Missing in settlement' },
  { value: 'MISSING_INTERNALLY', label: 'Missing internally' },
  { value: 'AMOUNT_MISMATCH', label: 'Amount mismatch' },
  { value: 'DUPLICATE', label: 'Duplicate' },
].map(Object.freeze));

export function classify(reference) {
  const purchase = purchases.find((record) => record.reference === reference);
  const rows = settlement.filter((record) => record.reference === reference);
  if (rows.length > 1) return 'DUPLICATE';
  if (!purchase) return 'MISSING_INTERNALLY';
  if (rows.length === 0) return 'MISSING_IN_SETTLEMENT';
  return purchase.amountCentavos === rows[0].amountCentavos ? 'MATCHED' : 'AMOUNT_MISMATCH';
}

export function evidenceFor(reference) {
  return {
    purchase: purchases.find((record) => record.reference === reference) ?? null,
    rows: settlement.filter((record) => record.reference === reference),
    outcome: classify(reference),
  };
}

export const initialState = Object.freeze({
  mode: 'Explorer', selected: 'AMOUNT-001', predictions: Object.freeze({}),
  completed: Object.freeze({}), feedback: null, step: 0,
});

export function lessonReducer(state, action) {
  switch (action.type) {
    case 'mode':
      return { ...state, mode: action.mode };
    case 'select':
      return { ...state, selected: action.reference, feedback: null };
    case 'predict':
      return { ...state, predictions: { ...state.predictions, [state.selected]: action.value }, feedback: null };
    case 'check': {
      const expected = classify(state.selected);
      const correct = state.predictions[state.selected] === expected;
      return {
        ...state,
        feedback: { correct, reference: state.selected, expected },
        completed: correct ? { ...state.completed, [state.selected]: true } : state.completed,
      };
    }
    case 'step':
      return { ...state, step: action.step };
    case 'next': {
      const current = references.indexOf(state.selected);
      for (let offset = 1; offset <= references.length; offset += 1) {
        const reference = references[(current + offset) % references.length];
        if (!state.completed[reference]) return { ...state, selected: reference, feedback: null };
      }
      return state;
    }
    case 'reset':
      return { ...initialState, predictions: {}, completed: {} };
    default:
      return state;
  }
}
