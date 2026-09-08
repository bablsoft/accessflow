package com.bablsoft.accessflow.ai.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One question asked in an open conversation.
 *
 * <p>The {@code question} ceiling is the highest value {@code help_agent_config.max_question_chars}
 * can be set to, not its default: a flat 2,000 here would make every organization that raised the
 * setting unable to use what it configured. The organization's own limit is applied server-side after
 * this, where it truncates rather than rejects.
 *
 * <p>{@code routeName} is a <em>label</em> for the screen the user is on ("Review queue"), never a
 * URL. It is sanitized server-side before it reaches a model — anything carrying a path, a query
 * string or an id is dropped — so a client that sends {@code window.location} leaks nothing.
 */
record AskHelpChatRequest(
        @NotBlank(message = "{validation.help_chat.question.required}")
        @Size(max = 10000, message = "{validation.help_chat.question.length}")
        String question,

        @Size(max = 120, message = "{validation.help_chat.route_name.length}")
        String routeName) {
}
