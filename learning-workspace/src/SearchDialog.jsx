import { useEffect, useMemo, useRef, useState } from 'react';
import { MagnifyingGlass, ArrowUpRight, X } from './icons.js';
import { buildSearchIndex } from './content/course.js';

export function SearchDialog({ open, onClose, onNavigate, onSource, onGlossary }) {
  const dialog = useRef(null);
  const input = useRef(null);
  const [query, setQuery] = useState('');
  const entries = useMemo(buildSearchIndex, []);
  useEffect(() => {
    if (open && !dialog.current.open) { dialog.current.showModal(); input.current?.focus(); }
    if (!open && dialog.current.open) dialog.current.close();
  }, [open]);
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const matches = words.length ? entries.filter((entry) => words.every((word) => `${entry.title} ${entry.detail} ${entry.text}`.toLowerCase().includes(word))).slice(0, 40) : entries.filter((entry) => entry.type === 'Lesson').filter((_, index) => index % 3 === 0);
  function choose(entry) {
    onClose();
    if (entry.type === 'Source') onSource(entry.id);
    else if (entry.type === 'Glossary') onGlossary(entry.title);
    else onNavigate({ overview: false, mode: entry.type === 'Mission' ? 'Missions' : 'Course', moduleId: entry.moduleId, step: entry.step ?? 0 });
  }
  return <dialog ref={dialog} className="search-dialog" aria-labelledby="search-title" onCancel={onClose} onClose={onClose} onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}><div className="search-dialog-header"><h2 id="search-title">Search the course</h2><button className="icon-button" aria-label="Close search" onClick={onClose}><X size={20} /></button></div><label className="search-field"><MagnifyingGlass size={20} /><span className="sr-only">Search lessons, missions, glossary, and code</span><input ref={input} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search lessons, missions, glossary, code…" /></label><p className="search-caption" aria-live="polite">{words.length ? `${matches.length}${matches.length === 40 ? '+' : ''} results` : 'Browse module introductions'}</p><div className="search-results">{matches.length ? matches.map((entry) => <button key={`${entry.type}-${entry.id}`} onClick={() => choose(entry)}><span className="search-type">{entry.type}</span><div><strong>{entry.title}</strong><p>{entry.detail}</p></div><ArrowUpRight size={17} /></button>) : <div className="empty-state"><h3>No results found.</h3><p>Try a concept such as “version”, “retry”, “MongoDB”, or “checksum”.</p></div>}</div><footer>Tab through results · Enter to open · Esc to close</footer></dialog>;
}
