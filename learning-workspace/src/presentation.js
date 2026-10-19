export const money = (centavos) => (centavos / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const explanations = {
  MATCHED: 'The reference appears once in each dataset, and both amounts are equal.',
  MISSING_IN_SETTLEMENT: 'We have an internal purchase, but no settlement row carries this reference.',
  MISSING_INTERNALLY: 'The settlement contains this reference, but our purchases do not.',
  AMOUNT_MISMATCH: 'The reference connects the two records, but the settlement amount differs from our purchase.',
  DUPLICATE: 'This reference appears in more than one settlement row. Duplicate takes precedence, even if the amounts agree.',
};
export const hints = {
  MATCHED: 'Find the reference on both sides, then compare the amounts.',
  MISSING_IN_SETTLEMENT: 'Search all settlement rows for this purchase reference.',
  MISSING_INTERNALLY: 'Search our internal purchases for this settlement reference.',
  AMOUNT_MISMATCH: 'The references agree. Check the two amounts carefully.',
  DUPLICATE: 'Count the settlement rows for this reference before comparing amounts.',
};
