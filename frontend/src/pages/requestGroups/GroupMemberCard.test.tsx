import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DndContext } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import type { ApiConnector, Datasource } from '@/types/api';
import '@/i18n';
import { GroupMemberCard } from './GroupMemberCard';
import { newMember, type DraftMember } from './groupBuilder';

const datasources = [{ id: 'ds-1', name: 'prod-db', db_type: 'POSTGRESQL' }] as Datasource[];
const connectors = [{ id: 'c-1', name: 'CRM' }] as ApiConnector[];

function renderCard(member: DraftMember, over: { readOnly?: boolean } = {}) {
  const onEdit = vi.fn();
  const onRemove = vi.fn();
  render(
    <DndContext>
      <SortableContext items={[member.key]} strategy={verticalListSortingStrategy}>
        <GroupMemberCard
          member={member}
          index={0}
          datasources={datasources}
          connectors={connectors}
          onEdit={onEdit}
          onRemove={onRemove}
          {...over}
        />
      </SortableContext>
    </DndContext>,
  );
  return { onEdit, onRemove };
}

describe('GroupMemberCard (compact summary, #559)', () => {
  it('summarises a QUERY member: target name, first SQL line, kind tag', () => {
    const member = newMember('QUERY');
    member.datasourceId = 'ds-1';
    member.sqlText = 'SELECT *\nFROM users';
    renderCard(member);

    expect(screen.getByText('prod-db')).toBeInTheDocument();
    expect(screen.getByText('SELECT *')).toBeInTheDocument();
    expect(screen.queryByText('FROM users')).toBeNull();
    expect(screen.getByText('Query')).toBeInTheDocument();
    // Complete member → no incomplete warning.
    expect(screen.queryByLabelText(/incomplete/i)).toBeNull();
  });

  it('summarises an API_CALL member and flags incomplete steps', () => {
    const member = newMember('API_CALL'); // no connector/path yet → incomplete
    renderCard(member);

    expect(screen.getByText('No target selected yet')).toBeInTheDocument();
    expect(screen.getByText(/open Edit to compose/i)).toBeInTheDocument();
    expect(screen.getAllByLabelText(/incomplete/i).length).toBeGreaterThan(0);
  });

  it('shows the verb/path preview once authored', () => {
    const member = newMember('API_CALL');
    member.connectorId = 'c-1';
    member.verb = 'POST';
    member.requestPath = '/v1/tickets';
    renderCard(member);

    expect(screen.getByText('CRM')).toBeInTheDocument();
    expect(screen.getByText('POST /v1/tickets')).toBeInTheDocument();
  });

  it('wires the Edit and Remove actions', () => {
    const member = newMember('QUERY');
    const { onEdit, onRemove } = renderCard(member);

    fireEvent.click(screen.getByTestId('group-member-0-edit'));
    expect(onEdit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByLabelText('Remove step 1'));
    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it('hides drag handle and actions when read-only', () => {
    const member = newMember('QUERY');
    renderCard(member, { readOnly: true });

    expect(screen.queryByTestId('group-member-0-edit')).toBeNull();
    expect(screen.queryByLabelText('Remove step 1')).toBeNull();
    expect(screen.queryByLabelText('Reorder step 1')).toBeNull();
  });
});
