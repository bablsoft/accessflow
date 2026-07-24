import type {
  QueryStatus,
  RequestGroupItemStatus,
  RequestGroupStatus,
  RiskLevel,
  UserNotificationEventType,
} from './api';

export type WsEventName =
  | 'query.status_changed'
  | 'review.new_request'
  | 'review.decision_made'
  | 'query.executed'
  | 'ai.analysis_complete'
  | 'query.estimate_complete'
  | 'notification.created'
  | 'anomaly.detected'
  | 'collab.joined'
  | 'collab.presence'
  | 'collab.sync'
  | 'collab.awareness'
  | 'collab.denied'
  | 'collab.comment'
  | 'attestation.campaign_opened'
  | 'request_group.status_changed'
  | 'request_group.item_executed';

export type ReviewDecision = 'APPROVED' | 'REJECTED' | 'REQUESTED_CHANGES';

export type CommentChangeType = 'ADDED' | 'REPLIED' | 'RESOLVED' | 'REOPENED';

/** A co-author present in a query's collaboration room (one row per user). */
export interface CollabMember {
  user_id: string;
  display_name: string;
  color: string;
}

export interface WsEventPayloadMap {
  'query.status_changed': {
    query_id: string;
    old_status: QueryStatus;
    new_status: QueryStatus;
  };
  'review.new_request': {
    query_id: string;
    risk_level: RiskLevel | null;
    submitter: string | null;
    datasource: string | null;
  };
  'review.decision_made': {
    query_id: string;
    decision: ReviewDecision;
    reviewer: string | null;
    comment: string | null;
  };
  'query.executed': {
    query_id: string;
    rows_affected: number | null;
    duration_ms: number;
  };
  'ai.analysis_complete': {
    query_id: string;
    risk_level: RiskLevel | null;
    risk_score: number | null;
  };
  'query.estimate_complete': {
    query_id: string;
    supported: boolean;
  };
  'notification.created': {
    notification_id: string;
    event_type: UserNotificationEventType;
    query_id: string | null;
    created_at: string;
  };
  'anomaly.detected': {
    anomaly_id: string;
    user_id: string;
    datasource_id: string;
    feature: string;
    score: number;
  };
  // Acknowledgement sent only to the joining session. `seed` is true for the first joiner of a
  // fresh room — that client seeds the shared document from the query's SQL.
  'collab.joined': {
    query_id: string;
    seed: boolean;
    self: CollabMember;
    participants: CollabMember[];
  };
  // Roster change broadcast to the other members of a room.
  'collab.presence': {
    query_id: string;
    participants: CollabMember[];
  };
  // Opaque, relayed Yjs document update (base64) from a peer.
  'collab.sync': {
    query_id: string;
    from_user_id: string;
    update: string;
  };
  // Opaque, relayed Yjs awareness update (base64) — cursors / selections / presence.
  'collab.awareness': {
    query_id: string;
    from_user_id: string;
    update: string;
  };
  'collab.denied': {
    query_id: string;
    reason: string;
  };
  'collab.comment': {
    query_id: string;
    comment_id: string;
    change_type: CommentChangeType;
    actor_id: string;
  };
  // Fired when an admin opens an attestation campaign (AF-384). Delivered only to the
  // campaign's eligible reviewers; carries the campaign id + name for toasts/invalidation.
  'attestation.campaign_opened': {
    campaign_id: string;
    name: string;
    due_at: string | null;
  };
  // Fired whenever a request group transitions state (AF-501).
  'request_group.status_changed': {
    request_group_id: string;
    old_status: RequestGroupStatus;
    new_status: RequestGroupStatus;
  };
  // Fired per member as the executor advances through an approved group's ordered sequence (AF-501).
  'request_group.item_executed': {
    request_group_id: string;
    item_id: string;
    sequence_order: number;
    status: RequestGroupItemStatus;
  };
}

export interface WsEnvelope<E extends WsEventName = WsEventName> {
  event: E;
  timestamp: string;
  data: WsEventPayloadMap[E];
}

/** Frames the client sends back over /ws — the collaboration protocol. */
export type CollabOutboundFrame =
  | { type: 'collab.join'; query_id: string }
  | { type: 'collab.leave'; query_id: string }
  | { type: 'collab.sync'; query_id: string; update: string }
  | { type: 'collab.awareness'; query_id: string; update: string };

export const WS_EVENT_NAMES: ReadonlyArray<WsEventName> = [
  'query.status_changed',
  'review.new_request',
  'review.decision_made',
  'query.executed',
  'ai.analysis_complete',
  'query.estimate_complete',
  'notification.created',
  'anomaly.detected',
  'collab.joined',
  'collab.presence',
  'collab.sync',
  'collab.awareness',
  'collab.denied',
  'collab.comment',
  'attestation.campaign_opened',
  'request_group.status_changed',
  'request_group.item_executed',
];
