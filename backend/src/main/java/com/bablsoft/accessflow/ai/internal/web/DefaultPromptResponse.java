package com.bablsoft.accessflow.ai.internal.web;

/**
 * The built-in analyzer system-prompt template, served to the admin UI so it can pre-fill / reset a
 * configuration's custom prompt.
 */
record DefaultPromptResponse(String template) {
}
