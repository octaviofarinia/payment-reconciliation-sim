import { useEffect, useRef, useState } from 'react';
import { X, Code, MagnifyingGlass, ArrowUpRight } from './icons.js';
import { sources, sourceById } from './content/course.js';
import { evidenceFor } from './lesson.js';

export function SourceCode({ source, selected, compact = false }) {
  const pre = useRef(null);
  useEffect(() => {
    const highlighted = pre.current?.querySelector('.source-highlight');
    if (highlighted) pre.current.scrollTop = Math.max(0, highlighted.offsetTop - pre.current.offsetTop - 65);
  }, [source.id, selected]);
  const outcome = evidenceFor(selected).outcome;
  return <pre ref={pre} tabIndex="0" className={`source-code ${compact ? 'inline-code' : ''}`} aria-label={`${source.title} source code`}><code>{source.content.split('\n').map((line, index) => {
    const focus = source.id === 'comparator' ? line.includes(`return Outcome.${outcome};`) : index + 1 >= source.focus[0] && index + 1 <= source.focus[1];
    return <span key={index} className={focus ? 'source-highlight' : ''}><span className="line-number" aria-hidden="true">{index + 1}</span>{line || ' '}</span>;
  })}</code></pre>;
}

export function SourceDialog({ open, onClose, selected, sourceId = 'comparator' }) {
  const dialog = useRef(null);
  const source = sourceById(sourceId);
  useEffect(() => {
    if (open && !dialog.current.open) dialog.current.showModal();
    if (!open && dialog.current.open) dialog.current.close();
  }, [open]);
  return <dialog ref={dialog} className="source-dialog" onCancel={onClose} onClose={onClose} onClick={(event) => { if (event.target === event.currentTarget) onClose(); }} aria-labelledby="source-title">
    <div className="dialog-content"><header><div><p className="eyebrow">FROM THE ACTUAL PROJECT · {source.group}</p><h2 id="source-title"><Code size={24} /> {source.title}</h2></div><button className="icon-button" aria-label="Close source code" onClick={onClose}><X size={24} /></button></header>
    <p className="source-explanation">{source.note}</p><p className="source-path">{source.path}</p><SourceCode source={source} selected={selected} />
    <footer>Exact source snapshot from this checkout. Highlighted lines connect to the lesson. This view does not execute Java, Terraform, or cloud actions.</footer></div>
  </dialog>;
}

export function CodeExplorer({ module, selected, sourceId, onSelectSource, onSource }) {
  const [query, setQuery] = useState('');
  const source = sourceById(sourceId || module.sourceIds[0]);
  const visible = sources.filter((entry) => `${entry.title} ${entry.path} ${entry.note}`.toLowerCase().includes(query.toLowerCase()));
  return <div className="code-explorer"><div className="explorer-section-heading"><div><p className="eyebrow">THE REPOSITORY, WITH CONTEXT</p><h1>Meet the real code.</h1></div><span className="small muted">{sources.length} source files</span></div>
    <p className="code-intro">Inspect the boundaries behind this module. These are exact project files, with the relevant lines highlighted.</p>
    <label className="search-field"><MagnifyingGlass size={18} /><span className="sr-only">Filter source files</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Find a file, package, or responsibility…" /></label>
    <div className="code-browser"><nav className="source-file-list" aria-label="Source files">{visible.length ? visible.map((entry) => <button key={entry.id} aria-pressed={entry.id === source.id} className={entry.id === source.id ? 'selected' : ''} onClick={() => onSelectSource(entry.id)}><span>{entry.group}</span><strong>{entry.title}</strong>{module.sourceIds.includes(entry.id) && <small>In this module</small>}</button>) : <p className="empty-state">No source files match “{query}”.</p>}</nav><section className="code-file"><div className="code-file-header"><div><p className="eyebrow">{source.group}</p><h2>{source.title}</h2></div><button className="icon-button" aria-label="Expand source code" onClick={() => onSource(source.id)}><ArrowUpRight size={18} /></button></div><p className="code-file-note">{source.note}</p><p className="source-path">{source.path}</p><SourceCode source={source} selected={selected} compact /><p className="code-footnote">Source snapshot · Highlighted lines {source.focus.join('–')} · Browser models run separately.</p></section></div>
  </div>;
}
