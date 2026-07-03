import type {
  RequestGroupItem,
  RequestGroupItemBody,
  RequestGroupTargetKind,
} from '@/types/api';
import {
  compositionFromSaved,
  compositionToSubmit,
  newComposition,
  type ApiRequestComposition,
} from '@/utils/apiRequestComposition';

/**
 * A draft member in the group builder. Carries a stable client-side `key` for dnd-kit + React keys
 * (the server has no id until the DRAFT is persisted). Holds both QUERY and API_CALL fields; only
 * the ones relevant to `targetKind` are serialized onto the wire.
 */
export interface DraftMember {
  key: string;
  targetKind: RequestGroupTargetKind;
  // QUERY fields
  datasourceId: string | null;
  sqlText: string;
  transactional: boolean;
  // API_CALL fields
  connectorId: string | null;
  operationId: string | null;
  verb: string;
  requestPath: string;
  composition: ApiRequestComposition;
  // pre-existing AI risk (when editing a saved DRAFT)
  aiRiskLevel: RequestGroupItem['ai_risk_level'];
  aiRiskScore: RequestGroupItem['ai_risk_score'];
}

let counter = 0;
export function newKey(): string {
  counter += 1;
  return `m-${Date.now()}-${counter}`;
}

export function newMember(targetKind: RequestGroupTargetKind): DraftMember {
  return {
    key: newKey(),
    targetKind,
    datasourceId: null,
    sqlText: '',
    transactional: false,
    connectorId: null,
    operationId: null,
    verb: 'GET',
    requestPath: '',
    composition: newComposition(),
    aiRiskLevel: null,
    aiRiskScore: null,
  };
}

/** Hydrates the builder from a persisted group's items (for editing a DRAFT). */
export function memberFromItem(item: RequestGroupItem): DraftMember {
  return {
    key: item.id || newKey(),
    targetKind: item.target_kind,
    datasourceId: item.datasource_id,
    sqlText: item.sql_text ?? '',
    transactional: item.transactional ?? false,
    connectorId: item.api_connector_id,
    operationId: item.operation_id,
    verb: item.verb ?? 'GET',
    requestPath: item.request_path ?? '',
    composition: compositionFromSaved(item),
    aiRiskLevel: item.ai_risk_level,
    aiRiskScore: item.ai_risk_score,
  };
}

export function memberToBody(m: DraftMember): RequestGroupItemBody {
  if (m.targetKind === 'QUERY') {
    return {
      target_kind: 'QUERY',
      datasource_id: m.datasourceId,
      sql_text: m.sqlText,
      transactional: m.transactional,
    };
  }
  return {
    target_kind: 'API_CALL',
    transactional: false,
    api_connector_id: m.connectorId,
    operation_id: m.operationId,
    verb: m.verb,
    request_path: m.requestPath,
    ...compositionToSubmit(m.composition),
  };
}

/** A member is complete enough to submit when its kind-specific required fields are filled. */
export function memberValid(m: DraftMember): boolean {
  if (m.targetKind === 'QUERY') {
    return !!m.datasourceId && m.sqlText.trim().length > 0 && m.sqlText.length <= 100000;
  }
  return (
    !!m.connectorId &&
    m.verb.trim().length > 0 &&
    m.verb.length <= 16 &&
    m.requestPath.trim().length > 0 &&
    m.requestPath.length <= 4000
  );
}
