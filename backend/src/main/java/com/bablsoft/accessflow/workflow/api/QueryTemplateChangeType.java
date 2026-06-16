package com.bablsoft.accessflow.workflow.api;

/**
 * How a {@link QueryTemplateVersionView} snapshot came to be (AF-442).
 *
 * <ul>
 *   <li>{@code CREATED} — the first version, written when the template is created.</li>
 *   <li>{@code UPDATED} — a content-changing edit; identical re-saves are not recorded.</li>
 *   <li>{@code RESTORED} — the template was rolled back to a prior version (history is preserved —
 *       restore writes a new version rather than deleting any).</li>
 * </ul>
 */
public enum QueryTemplateChangeType {
    CREATED,
    UPDATED,
    RESTORED
}
