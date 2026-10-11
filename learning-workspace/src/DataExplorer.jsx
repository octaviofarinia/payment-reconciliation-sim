import { useMemo } from 'react';
import { Background, ReactFlow, Handle, Position } from '@xyflow/react';
import { CheckCircle } from './icons.js';
import '@xyflow/react/dist/style.css';
import { purchases, settlement } from './lesson.js';
import { money } from './presentation.js';

function LedgerNode({ data }) {
  return <section className="ledger-node" aria-label={data.kind === 'purchase' ? 'Our purchases' : 'Settlement file'}>
    <h2>{data.kind === 'purchase' ? 'Our purchases' : 'Settlement file'} <span>· {data.rows.length}</span></h2>
    <div className="ledger-columns"><span>Reference</span><span>ARS</span></div>
    <div role="list">{data.rows.map((row, index) => <div role="listitem" className={`ledger-row ${row.reference === data.selected ? 'selected' : ''}`} key={`${row.reference}-${index}`}>
      <button className="nodrag nopan" aria-label={`${data.kind === 'purchase' ? 'Purchase' : 'Settlement row ' + row.rowNumber}: ${row.reference}, ARS ${money(row.amountCentavos)}`} aria-pressed={row.reference === data.selected} onClick={() => data.onSelect(row.reference)}><span>{row.reference}</span><span>{money(row.amountCentavos)}</span></button>
      <Handle type={data.kind === 'purchase' ? 'source' : 'target'} position={data.kind === 'purchase' ? Position.Right : Position.Left} id={String(index)} isConnectable={false} className={row.reference === data.selected ? 'selected-handle' : ''} />
      {data.completed[row.reference] && <CheckCircle className="row-complete" size={13} weight="fill" aria-label="Classified" />}
    </div>)}</div>
  </section>;
}
const nodeTypes = { ledger: LedgerNode };

const ledgerViewport = { x: 0, y: 4, zoom: 1 };

export function DataExplorer({ selected, onSelect, completed }) {
  const nodes = useMemo(() => [
    { id: 'purchases', type: 'ledger', position: { x: 0, y: 0 }, data: { kind: 'purchase', rows: purchases, selected, onSelect, completed } },
    { id: 'settlement', type: 'ledger', position: { x: 500, y: 0 }, data: { kind: 'settlement', rows: settlement, selected, onSelect, completed } },
  ], [selected, onSelect, completed]);
  const edges = useMemo(() => settlement.flatMap((row, index) => {
    const sourceIndex = purchases.findIndex((purchase) => purchase.reference === row.reference);
    return sourceIndex < 0 ? [] : [{ id: `link-${index}`, source: 'purchases', target: 'settlement', sourceHandle: String(sourceIndex), targetHandle: String(index), style: { stroke: row.reference === selected ? '#ffb547' : '#405268', strokeWidth: row.reference === selected ? 2.4 : 1.2, opacity: row.reference === selected ? 1 : 0.5 }, zIndex: row.reference === selected ? 2 : 1, ariaLabel: `${row.reference} connects purchase to settlement row ${row.rowNumber}` }];
  }), [selected]);
  return <>
    <div className="graph-desktop" aria-label="Purchase and settlement relationship explorer"><div className="graph-stage"><ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} viewport={ledgerViewport} minZoom={0.5} maxZoom={1} nodesDraggable={false} nodesConnectable={false} elementsSelectable={false} panOnDrag={false} zoomOnScroll={false} zoomOnPinch={false} zoomOnDoubleClick={false} preventScrolling={false} colorMode="dark"><Background color="#24384a" bgColor="#0e1923" gap={24} size={1} /></ReactFlow></div></div>
    <div className="mobile-ledgers">{[{ title: 'Our purchases', rows: purchases }, { title: 'Settlement file', rows: settlement }].map(({ title, rows }) => <section key={title}><h2>{title} <span>· {rows.length}</span></h2><div className="ledger-columns"><span>Reference</span><span>ARS</span></div>{rows.map((row, index) => <button key={index} className={`mobile-record ${selected === row.reference ? 'selected' : ''}`} aria-label={`${title === 'Our purchases' ? 'Purchase' : 'Settlement row ' + row.rowNumber}: ${row.reference}, ARS ${money(row.amountCentavos)}`} aria-pressed={selected === row.reference} onClick={() => onSelect(row.reference)}><span>{row.reference}</span><span>{money(row.amountCentavos)}</span></button>)}</section>)}<p className="small muted">Rows with the same reference are connected. Repeated settlement references have multiple evidence rows.</p></div>
  </>;
}
