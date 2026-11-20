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
      className="u-text-xs" style={{ width: NODE_WIDTH, border: `1px solid ${isSelected ? 'var(--accent)' : 'var(--border)'}`, borderRadius: 6, background: 'var(--bg)', boxShadow: isSelected
          ? '0 0 0 2px var(--accent-soft, rgba(99,102,241,0.18))'
          : '0 1px 2px rgba(0,0,0,0.04)', overflow: 'hidden', cursor: 'pointer' }}
    >
      <Handle type="target" position={Position.Left} style={{ background: 'var(--border)' }} />
      <Handle type="source" position={Position.Right} style={{ background: 'var(--border)' }} />
      <div
        className="u-fw-600 u-mono u-text-xs" style={{ padding: '8px 12px', background: 'var(--bg-sunken)', borderBottom: '1px solid var(--border)' }}
      >
        <span className="muted u-fw-400">{data.schema}.</span>
        {data.table}
      </div>
      <div style={{ maxHeight: MAX_NODE_HEIGHT - HEADER_HEIGHT, overflowY: 'auto' }}>
        {data.columns.map((c) => (
          <div
            key={c.name}
            className="u-flex u-justify-between u-gap-2 u-mono" style={{ padding: '3px 12px', borderBottom: '1px solid var(--border-faint, transparent)' }}
          >
            <span>
              {c.primary_key && (
                <span
                  className="muted u-text-2xs u-fw-600"
                  style={{ marginRight: 6 }}
                >
                  {data.pkLabel}
                </span>
              )}
              {c.name}
            </span>
            <span className="muted u-text-2xs">{c.type}</span>
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
      className="u-w-full" style={{ height: 560, border: '1px solid var(--border)', borderRadius: 8 }}
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
          className="muted u-text-2xs"
          style={{ padding: '6px 12px', borderTop: '1px solid var(--border)' }}
        >
          {helpText}
        </div>
      )}
    </div>
  );
}
