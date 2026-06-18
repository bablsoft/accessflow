import * as Y from 'yjs';
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
  removeAwarenessStates,
} from 'y-protocols/awareness';
import type { Extension } from '@codemirror/state';
import { yCollab } from 'y-codemirror.next';
import { websocketManager } from './websocketManager';
import type { CollabMember } from '@/types/ws';

export interface CollabUser {
  id: string;
  name: string;
}

export interface QueryCollabProviderOptions {
  queryId: string;
  user: CollabUser;
  initialText: string;
  onPresence?: (members: CollabMember[]) => void;
  onDenied?: () => void;
  manager?: Pick<typeof websocketManager, 'send' | 'subscribe'>;
}

type AwarenessChange = { added: number[]; updated: number[]; removed: number[] };

/**
 * Binds a Yjs document + awareness to the AccessFlow `/ws` relay for one query's collaboration
 * room. The backend is an opaque relay: this provider exchanges full document state on each new
 * peer (Yjs updates are commutative + idempotent, so this converges) and relays awareness
 * (cursors/selections) verbatim. The first joiner of a fresh room seeds the document from the
 * query's current SQL.
 */
export class QueryCollabProvider {
  readonly doc: Y.Doc;
  readonly text: Y.Text;
  readonly awareness: Awareness;

  private readonly queryId: string;
  private readonly user: CollabUser;
  private readonly initialText: string;
  private readonly manager: Pick<typeof websocketManager, 'send' | 'subscribe'>;
  private readonly onPresence?: (members: CollabMember[]) => void;
  private readonly onDenied?: () => void;
  private readonly knownPeers = new Set<string>();
  private readonly unsubscribers: Array<() => void> = [];
  private seeded = false;
  private destroyed = false;

  constructor(opts: QueryCollabProviderOptions) {
    this.queryId = opts.queryId;
    this.user = opts.user;
    this.initialText = opts.initialText;
    this.onPresence = opts.onPresence;
    this.onDenied = opts.onDenied;
    this.manager = opts.manager ?? websocketManager;

    this.doc = new Y.Doc();
    this.text = this.doc.getText('sql');
    this.awareness = new Awareness(this.doc);
    this.awareness.setLocalStateField('user', { name: this.user.name, color: '#2563eb' });

    this.doc.on('update', this.handleLocalDocUpdate);
    this.awareness.on('update', this.handleLocalAwarenessUpdate);
    this.subscribeInbound();
    this.manager.send({ type: 'collab.join', query_id: this.queryId });
  }

  /** The CodeMirror extension that binds the editor to this provider's shared text + awareness. */
  extension(): Extension {
    return yCollab(this.text, this.awareness);
  }

  destroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    removeAwarenessStates(this.awareness, [this.awareness.clientID], 'destroy');
    this.manager.send({ type: 'collab.leave', query_id: this.queryId });
    this.doc.off('update', this.handleLocalDocUpdate);
    this.awareness.off('update', this.handleLocalAwarenessUpdate);
    for (const off of this.unsubscribers) off();
    this.unsubscribers.length = 0;
    this.awareness.destroy();
    this.doc.destroy();
  }

  private subscribeInbound(): void {
    this.unsubscribers.push(
      this.manager.subscribe('collab.joined', (data) => {
        if (data.query_id !== this.queryId) return;
        this.awareness.setLocalStateField('user', {
          name: this.user.name,
          color: data.self.color,
        });
        if (data.seed && this.text.length === 0 && !this.seeded) {
          this.seeded = true;
          if (this.initialText) this.text.insert(0, this.initialText);
        }
        this.handleRoster(data.participants);
      }),
      this.manager.subscribe('collab.presence', (data) => {
        if (data.query_id !== this.queryId) return;
        this.handleRoster(data.participants);
      }),
      this.manager.subscribe('collab.sync', (data) => {
        if (data.query_id !== this.queryId) return;
        Y.applyUpdate(this.doc, base64ToBytes(data.update), this);
      }),
      this.manager.subscribe('collab.awareness', (data) => {
        if (data.query_id !== this.queryId) return;
        applyAwarenessUpdate(this.awareness, base64ToBytes(data.update), this);
      }),
      this.manager.subscribe('collab.denied', (data) => {
        if (data.query_id !== this.queryId) return;
        this.onDenied?.();
      }),
    );
  }

  private handleRoster(participants: CollabMember[]): void {
    let newPeer = false;
    for (const member of participants) {
      if (member.user_id === this.user.id) continue;
      if (!this.knownPeers.has(member.user_id)) {
        this.knownPeers.add(member.user_id);
        newPeer = true;
      }
    }
    // A peer we haven't synced with appeared — send our full state so they converge.
    if (newPeer) this.broadcastState();
    this.onPresence?.(participants);
  }

  private broadcastState(): void {
    this.manager.send({
      type: 'collab.sync',
      query_id: this.queryId,
      update: bytesToBase64(Y.encodeStateAsUpdate(this.doc)),
    });
  }

  private readonly handleLocalDocUpdate = (update: Uint8Array, origin: unknown): void => {
    // Skip updates we just applied from a peer (origin === this) to avoid an echo loop.
    if (origin === this) return;
    this.manager.send({
      type: 'collab.sync',
      query_id: this.queryId,
      update: bytesToBase64(update),
    });
  };

  private readonly handleLocalAwarenessUpdate = (change: AwarenessChange,
                                                 origin: unknown): void => {
    if (origin === this) return;
    const changed = [...change.added, ...change.updated, ...change.removed];
    this.manager.send({
      type: 'collab.awareness',
      query_id: this.queryId,
      update: bytesToBase64(encodeAwarenessUpdate(this.awareness, changed)),
    });
  };
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
