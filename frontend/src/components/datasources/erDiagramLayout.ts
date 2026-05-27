import type { Edge, Node } from '@xyflow/react';
import dagre from 'dagre';
import type { SchemaNamespace } from '@/types/api';

export const NODE_WIDTH = 240;
export const HEADER_HEIGHT = 36;
export const MAX_NODE_HEIGHT = 320;

const ROW_HEIGHT = 22;
const FOOTER_PADDING = 12;
const HIGHLIGHT_EDGE = 1;
const DIMMED_EDGE = 0.18;

export interface TableNodeData extends Record<string, unknown> {
  schema: string;
  table: string;
  columns: { name: string; type: string; primary_key: boolean }[];
  pkLabel: string;
  selectedNodeId: string | null;
  onSelect: (id: string) => void;
}

function nodeId(schema: string, table: string): string {
  return `${schema}.${table}`;
}

function measureNodeHeight(columnCount: number): number {
  return Math.min(
    HEADER_HEIGHT + columnCount * ROW_HEIGHT + FOOTER_PADDING,
    MAX_NODE_HEIGHT,
  );
}

function resolveTargetNodeId(
  schemas: SchemaNamespace[],
  preferredSchema: string,
  toTable: string,
): string | null {
  const preferred = schemas.find((s) => s.name === preferredSchema);
  if (preferred?.tables.some((t) => t.name === toTable)) {
    return nodeId(preferredSchema, toTable);
  }
  for (const s of schemas) {
    if (s.tables.some((t) => t.name === toTable)) {
      return nodeId(s.name, toTable);
    }
  }
  return null;
}

export function buildLayout(
  schemas: SchemaNamespace[],
  selectedNodeId: string | null,
  onSelect: (id: string) => void,
  pkLabel: string,
  fkLabelTemplate: (from: string, to: string) => string,
): { nodes: Node<TableNodeData>[]; edges: Edge[] } {
  const tableIds = new Set<string>();
  for (const s of schemas) {
    for (const t of s.tables) {
      tableIds.add(nodeId(s.name, t.name));
    }
  }

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 80, marginx: 20, marginy: 20 });

  const rawNodes: Node<TableNodeData>[] = [];
  for (const s of schemas) {
    for (const t of s.tables) {
      const id = nodeId(s.name, t.name);
      const height = measureNodeHeight(t.columns.length);
      g.setNode(id, { width: NODE_WIDTH, height });
      rawNodes.push({
        id,
        type: 'table',
        position: { x: 0, y: 0 },
        data: {
          schema: s.name,
          table: t.name,
          columns: t.columns,
          pkLabel,
          selectedNodeId,
          onSelect,
        },
      });
    }
  }

  const edges: Edge[] = [];
  for (const s of schemas) {
    for (const t of s.tables) {
      const fromId = nodeId(s.name, t.name);
      for (const fk of t.foreign_keys) {
        const toId = resolveTargetNodeId(schemas, s.name, fk.to_table) ?? `${s.name}.${fk.to_table}`;
        if (!tableIds.has(toId)) {
          continue;
        }
        const edgeId = `${fromId}.${fk.from_column}->${toId}.${fk.to_column}`;
        g.setEdge(fromId, toId);
        const touchesSelected =
          selectedNodeId === null || fromId === selectedNodeId || toId === selectedNodeId;
        edges.push({
          id: edgeId,
          source: fromId,
          target: toId,
          label: fkLabelTemplate(fk.from_column, fk.to_column),
          labelStyle: { fontSize: 10, fill: 'var(--fg-muted)' },
          labelBgStyle: { fill: 'var(--bg)' },
          style: {
            stroke: 'var(--border-strong)',
            strokeWidth: touchesSelected ? 1.5 : 1,
            opacity: touchesSelected ? HIGHLIGHT_EDGE : DIMMED_EDGE,
          },
        });
      }
    }
  }

  dagre.layout(g);

  const nodes = rawNodes.map((n) => {
    const { x, y, width, height } = g.node(n.id);
    return {
      ...n,
      position: { x: x - width / 2, y: y - height / 2 },
    };
  });

  return { nodes, edges };
}
