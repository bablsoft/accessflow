import type { QueryStatus, RiskLevel } from './api';

export type WsEventName =
  | 'query.status_changed'
  | 'review.new_request'
  | 'review.decision_made'
  | 'query.executed'
  | 'ai.analysis_complete';

export type ReviewDecision = 'APPROVED' | 'REJECTED' | 'REQUESTED_CHANGES';

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
}

export interface WsEnvelope<E extends WsEventName = WsEventName> {
  event: E;
  timestamp: string;
  data: WsEventPayloadMap[E];
}

export const WS_EVENT_NAMES: ReadonlyArray<WsEventName> = [
  'query.status_changed',
  'review.new_request',
  'review.decision_made',
  'query.executed',
  'ai.analysis_complete',
];
