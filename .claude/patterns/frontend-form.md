# Frontend form + validation parity

**When to use:** Any Ant Design `Form` that POSTs/PUTs to the API — and any time you add, change,
or remove a Bean Validation constraint on the backend request DTO it submits to.
**Canonical example (backend):** `backend/src/main/java/com/bablsoft/accessflow/security/internal/web/model/UpdateReviewPlanRequest.java:10`
**Canonical example (frontend):** `frontend/src/pages/admin/ReviewPlansPage.tsx:373`
**Tests:** `frontend/src/pages/admin/ReviewPlansPage.test.tsx`; backend half in the module's `*WebModelsTest.java`
**Related:** [rest-controller.md](rest-controller.md), [frontend-page.md](frontend-page.md), [backend-i18n.md](backend-i18n.md)

## Shape

The rule is a **bidirectional contract**: every backend constraint has a frontend rule and vice
versa, changed in the same commit. `UpdateReviewPlanRequest` ↔ `ReviewPlansPage` is the reference
pair:

```java
// UpdateReviewPlanRequest.java:11
@Size(min = 1, max = 255, message = "{validation.review_plan_name.max}") String name,
@Size(max = 2000, message = "{validation.review_plan_description.max}") String description,
@Min(value = 1, message = "{validation.review_plan_min_approvals.range}")
@Max(value = 10, message = "{validation.review_plan_min_approvals.range}")
Integer minApprovalsRequired,
@Min(value = 1, ...) @Max(value = 8760, ...) Integer approvalTimeoutHours,
```
```tsx
// ReviewPlansPage.tsx:373
<Form.Item name="name" label={t('admin.review_plans.label_name')}
           rules={[{ required: true, max: 255, whitespace: true }]} />       // :373
<Form.Item name="description" rules={[{ max: 2000 }]} />                     // :380
<Form.Item name="min_approvals_required"
           rules={[{ required: true, type: 'number', min: 1, max: 10 }]} />  // :403
<Form.Item name="approval_timeout_hours"
           rules={[{ required: true, type: 'number', min: 1, max: 8760 }]} />// :410
```

Note the field names differ by convention — Java `camelCase`, wire/form `snake_case`.

### Constraint translation table

| Backend | Frontend rule |
|---|---|
| `@NotBlank` | `{ required: true, whitespace: true }` |
| `@NotNull` | `{ required: true }` |
| `@Size(min, max)` on a String | `{ min, max }` |
| `@Size(min = 1, max = N)` | `{ required: true, max: N, whitespace: true }` |
| `@Min(a) @Max(b)` on a number | `{ type: 'number', min: a, max: b }` |
| `@Email` | `{ type: 'email' }` |
| `@Pattern(regexp)` | `{ pattern: /…/ }` |

## Required (acceptance checklist)

- [ ] Every backend constraint has a matching `Form.Item` rule, and every frontend rule has a
      matching backend constraint. **Same commit, both sides.** The server stays the source of
      truth; the client rule only spares the user a round-trip.
- [ ] Before touching a constraint, grep the DTO name to find the form that posts to it.
- [ ] Visible label via `Form.Item label={t(...)}`. A placeholder is **not** a label.
- [ ] Backend `message` is a `{key}` in `messages.properties` + all six locales
      ([backend-i18n.md](backend-i18n.md)).
- [ ] Field-level server errors surfaced when the `ProblemDetail` carries `error.path`.
- [ ] Submit handler typed (`(values: UpdateReviewPlanRequest) => Promise<void>`), never `any`.
- [ ] Submit button disabled while pending, with the spinner *inside the button* — not a
      full-page loader.
- [ ] Form state managed by AntD `Form`, never `useState`.

## Anti-patterns

- **Tightening a backend constraint without the frontend rule** → the user fills the form, submits,
  and gets a 400 for something the form could have caught. Worse, the toast may show a generic
  fallback instead of the field error.
- **Adding a frontend rule with no backend constraint** → the API accepts data the UI forbids, so
  any other client (Terraform provider, CI action, curl) can write invalid rows.
- **A placeholder instead of a label** → invisible to screen readers and gone as soon as the user
  types.
- **`useState` for form values** → loses AntD's validation, touched-state, and reset handling.
- **`as any` on the submit handler** → the wire contract is exactly what must stay typed.
- **Inline English in the backend `message`** → blocked by `backend/checkstyle.xml`.
- **Assuming field names match** → the DTO is `camelCase`, the wire is `snake_case`. A silently
  mismatched name sends `undefined` and the backend rejects it as null.

## Extending

For runtime validation of API *responses* with non-trivial shapes (AI analysis, datasource
schema), reach for `zod` rather than an `as` cast — add the dependency when first needed.

`.claude/hooks/pre-commit-check.sh` cannot check parity itself (it needs both files and a semantic
diff), so it treats a staged backend DTO without a staged frontend page as a warning. The real
check is this pattern plus review.
