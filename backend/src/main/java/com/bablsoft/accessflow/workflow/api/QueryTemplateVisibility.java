package com.bablsoft.accessflow.workflow.api;

/**
 * Who may read a saved query template. {@link #PRIVATE} rows are visible only to the owner;
 * {@link #TEAM} rows are visible to every user in the same organisation. The owner is always
 * the only one who may mutate or delete a template regardless of visibility.
 */
public enum QueryTemplateVisibility {
    PRIVATE,
    TEAM
}
