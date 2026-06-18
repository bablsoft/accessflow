import { describe, expect, it, vi } from 'vitest';
import * as Y from 'yjs';
import { QueryCollabProvider, type QueryCollabProviderOptions } from '../collabProvider';
import type { CollabMember } from '@/types/ws';

function updateBase64(text: string): string {
  const doc = new Y.Doc();
  doc.getText('sql').insert(0, text);
  const update = Y.encodeStateAsUpdate(doc);
  let binary = '';
  update.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function makeManager() {
  const handlers = new Map<string, (data: unknown) => void>();
  const send = vi.fn(() => true);
  const subscribe = vi.fn((event: string, handler: (data: unknown) => void) => {
    handlers.set(event, handler);
    return () => handlers.delete(event);
  });
  return {
    manager: { send, subscribe } as unknown as QueryCollabProviderOptions['manager'],
    send,
    subscribe,
    emit: (event: string, data: unknown) => handlers.get(event)?.(data),
    has: (event: string) => handlers.has(event),
  };
}

function build(overrides: { initialText?: string; onPresence?: (m: CollabMember[]) => void;
  onDenied?: () => void } = {}) {
  const m = makeManager();
  const provider = new QueryCollabProvider({
    queryId: 'q-1',
    user: { id: 'u-1', name: 'Ann' },
    initialText: overrides.initialText ?? 'SELECT 1',
    onPresence: overrides.onPresence,
    onDenied: overrides.onDenied,
    manager: m.manager,
  });
  return { m, provider };
}

const self: CollabMember = { user_id: 'u-1', display_name: 'Ann', color: '#abc' };
const peer: CollabMember = { user_id: 'u-2', display_name: 'Bob', color: '#def' };

describe('QueryCollabProvider', () => {
  it('sends a join frame and subscribes to collab events on construct', () => {
    const { m } = build();
    expect(m.send).toHaveBeenCalledWith({ type: 'collab.join', query_id: 'q-1' });
    expect(m.has('collab.joined')).toBe(true);
    expect(m.has('collab.sync')).toBe(true);
  });

  it('seeds the document from initial text when it is the first joiner', () => {
    const { m, provider } = build({ initialText: 'SELECT 42' });
    m.emit('collab.joined', { query_id: 'q-1', seed: true, self, participants: [self] });
    expect(provider.text.toString()).toBe('SELECT 42');
  });

  it('does not seed when it is not the first joiner', () => {
    const { m, provider } = build({ initialText: 'SELECT 42' });
    m.emit('collab.joined', { query_id: 'q-1', seed: false, self, participants: [self] });
    expect(provider.text.toString()).toBe('');
  });

  it('broadcasts full state when a new peer appears', () => {
    const { m } = build();
    m.send.mockClear();
    m.emit('collab.presence', { query_id: 'q-1', participants: [self, peer] });
    expect(m.send).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'collab.sync', query_id: 'q-1' }),
    );
  });

  it('applies an inbound sync update from a peer', () => {
    const { m, provider } = build({ initialText: '' });
    m.emit('collab.sync', { query_id: 'q-1', from_user_id: 'u-2', update: updateBase64('hello') });
    expect(provider.text.toString()).toContain('hello');
  });

  it('ignores frames for a different query', () => {
    const { m, provider } = build({ initialText: '' });
    m.emit('collab.sync', { query_id: 'other', from_user_id: 'u-2', update: updateBase64('nope') });
    expect(provider.text.toString()).toBe('');
  });

  it('reports presence to the callback', () => {
    const onPresence = vi.fn();
    const { m } = build({ onPresence });
    m.emit('collab.presence', { query_id: 'q-1', participants: [self, peer] });
    expect(onPresence).toHaveBeenCalledWith([self, peer]);
  });

  it('invokes onDenied when the join is rejected', () => {
    const onDenied = vi.fn();
    const { m } = build({ onDenied });
    m.emit('collab.denied', { query_id: 'q-1', reason: 'NOT_PERMITTED' });
    expect(onDenied).toHaveBeenCalled();
  });

  it('broadcasts local edits as sync frames', () => {
    const { m, provider } = build({ initialText: '' });
    m.send.mockClear();
    provider.text.insert(0, 'X');
    expect(m.send).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'collab.sync', query_id: 'q-1' }),
    );
  });

  it('sends a leave frame and stops applying frames after destroy', () => {
    const { m, provider } = build({ initialText: '' });
    provider.destroy();
    expect(m.send).toHaveBeenCalledWith({ type: 'collab.leave', query_id: 'q-1' });
    m.emit('collab.sync', { query_id: 'q-1', from_user_id: 'u-2', update: updateBase64('late') });
    expect(provider.text.toString()).toBe('');
  });
});
