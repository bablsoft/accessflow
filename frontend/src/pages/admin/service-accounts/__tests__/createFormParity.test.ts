import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  CREATE_FORM_CONSTRAINTS,
  KEY_FORM_CONSTRAINTS,
  UPDATE_FORM_CONSTRAINTS,
  type FieldConstraints,
} from '../serviceAccountForm';

/**
 * Validation parity (#871 ↔ #875): the create / update / key forms carry exactly the constraints
 * the backend request records declare, field for field. The Java records are the source of truth,
 * so a constraint added or dropped on one side without the other fails here.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const WEB_DIR = path.resolve(
  here,
  '../../../../../../backend/src/main/java/com/bablsoft/accessflow/serviceaccounts/internal/web',
);

const NOT_A_FORM_FIELD = new Set(['role', 'role_id', 'owner_user_id', 'mcp_tool_allow_list', 'active', 'clear']);

function snakeCase(camel: string): string {
  return camel.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
}

/** Per record component: the Bean Validation annotations, translated onto `FieldConstraints`. */
function constraintsOf(recordFile: string): Record<string, FieldConstraints> {
  const source = readFileSync(path.join(WEB_DIR, recordFile), 'utf8');
  const header = source.match(/public record \w+\(/);
  const start = (header?.index ?? 0) + (header?.[0].length ?? 0);
  const body = source.slice(start, source.indexOf(') {', start));
  const result: Record<string, FieldConstraints> = {};
  // Record components are separated by a blank line (or a bare comma for the unannotated ones).
  for (const component of body.split(/,\n\n|,\n(?=\s*\w+ \w+$)/m)) {
    const match = component.trim().match(/(\w+)$/);
    if (!match) continue;
    const name = snakeCase(match[1] ?? '');
    if (NOT_A_FORM_FIELD.has(name)) continue;
    const constraints: FieldConstraints = {};
    if (/@NotBlank\b/.test(component)) constraints.required = true;
    if (/@Email\b/.test(component)) constraints.email = true;
    const size = component.match(/@Size\(max = (\d+)/);
    if (size) constraints.max = Number(size[1]);
    if (/@Positive\b/.test(component)) constraints.positive = true;
    result[name] = constraints;
  }
  return result;
}

describe('service-account form ↔ backend validation parity', () => {
  it('the create form mirrors CreateServiceAccountRequest field for field', () => {
    expect(constraintsOf('CreateServiceAccountRequest.java')).toEqual(CREATE_FORM_CONSTRAINTS);
  });

  it('the overview and limits forms mirror UpdateServiceAccountRequest', () => {
    const backend = constraintsOf('UpdateServiceAccountRequest.java');
    // @Pattern("\\s*\\S[\\s\\S]*") is "non-blank when sent"; the form never sends a blank name.
    expect(backend).toEqual(UPDATE_FORM_CONSTRAINTS);
  });

  it('the issue and rotate modals mirror the key request records', () => {
    expect(constraintsOf('IssueServiceAccountKeyRequest.java')).toEqual({
      ...KEY_FORM_CONSTRAINTS,
      expires_at: {},
    });
    const rotate = constraintsOf('RotateServiceAccountKeyRequest.java');
    expect(rotate.name).toEqual(KEY_FORM_CONSTRAINTS.name);
    expect(rotate.application_name).toEqual(KEY_FORM_CONSTRAINTS.application_name);
    // grace_period is @AssertTrue-positive on the backend; the form's InputNumber min=1 mirrors it.
    expect(Object.keys(rotate)).toEqual(['name', 'expires_at', 'grace_period', 'application_name']);
  });
});
