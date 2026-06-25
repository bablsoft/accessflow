package com.bablsoft.accessflow.dashboard.api;

/**
 * Per-item lifecycle of an AI optimization suggestion in a user's dashboard backlog (AF-498).
 * A suggestion is implicitly {@code OPEN} until the user {@code DISMISSED}es it (not actionable) or
 * {@code APPLIED}s it (a draft query was created from it). Only {@code OPEN} suggestions appear in
 * the backlog widget.
 */
public enum DashboardSuggestionStatus {
    OPEN,
    APPLIED,
    DISMISSED
}
