import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Background,
  Controls,
  Handle,
  Position,
  ReactFlow,
  type Node,
  type NodeProps,
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { SchemaNamespace } from '@/types/api';
import {
  buildLayout,
  HEADER_HEIGHT,
  MAX_NODE_HEIGHT,
  NODE_WIDTH,
  type TableNodeData,
} from './erDiagramLayout';

interface ErDiagramProps {
  schemas: SchemaNamespace[];
  helpText?: string;
}

function TableNode({ id, data }: NodeProps<Node<TableNodeData>>) {
  const isSelected = data.selectedNodeId === id;
  return (
    <div
      onClick={() => data.onSelect(id)}
      style={{
        width: NODE_WIDTH,
        border: `1px solid ${isSelected ? 'var(--accent)' : 'var(--border)'}`,
        borderRadius: 6,
        background: 'var(--bg)',
        boxShadow: isSelected
          ? '0 0 0 2px var(--accent-soft, rgba(99,102,241,0.18))'
          : '0 1px 2px rgba(0,0,0,0.04)',
        fontSize: 12,
        overflow: 'hidden',
        cursor: 'pointer',
      }}
    >
      <Handle type="target" position={Position.Left} style={{ background: 'var(--border)' }} />
      <Handle type="source" position={Position.Right} style={{ background: 'var(--border)' }} />
      <div
        style={{
          padding: '8px 12px',
          background: 'var(--bg-sunken)',
          borderBottom: '1px solid var(--border)',
          fontWeight: 600,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
        }}
      >
        <span className="muted" style={{ fontWeight: 400 }}>{data.schema}.</span>
        {data.table}
      </div>
      <div style={{ maxHeight: MAX_NODE_HEIGHT - HEADER_HEIGHT, overflowY: 'auto' }}>
        {data.columns.map((c) => (
          <div
            key={c.name}
            style={{
              padding: '3px 12px',
              display: 'flex',
              justifyContent: 'space-between',
              gap: 8,
              fontFamily: 'var(--font-mono)',
              borderBottom: '1px solid var(--border-faint, transparent)',
            }}
          >
            <span>
              {c.primary_key && (
                <span
                  className="muted"
                  style={{ fontSize: 10, marginRight: 6, fontWeight: 600 }}
                >
                  {data.pkLabel}
                </span>
              )}
              {c.name}
            </span>
            <span className="muted" style={{ fontSize: 11 }}>{c.type}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

const nodeTypes: NodeTypes = { table: TableNode };

export function ErDiagram({ schemas, helpText }: ErDiagramProps) {
  const { t } = useTranslation();
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  const pkLabel = t('datasources.settings.er_diagram_node_pk');
  const fkLabelTemplate = useCallback(
    (from: string, to: string) =>
      t('datasources.settings.er_diagram_fk_label', { from, to }),
    [t],
  );

  const { nodes, edges } = useMemo(
    () => buildLayout(schemas, selectedNodeId, setSelectedNodeId, pkLabel, fkLabelTemplate),
    [schemas, selectedNodeId, pkLabel, fkLabelTemplate],
  );

  return (
    <div
      style={{ width: '100%', height: 560, border: '1px solid var(--border)', borderRadius: 8 }}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        nodesDraggable={false}
        nodesConnectable={false}
        edgesFocusable={false}
        elementsSelectable
        fitView
        proOptions={{ hideAttribution: true }}
        onPaneClick={() => setSelectedNodeId(null)}
      >
        <Background gap={16} />
        <Controls showInteractive={false} />
      </ReactFlow>
      {helpText && (
        <div
          className="muted"
          style={{ fontSize: 11, padding: '6px 12px', borderTop: '1px solid var(--border)' }}
        >
          {helpText}
        </div>
      )}
    </div>
  );
}
