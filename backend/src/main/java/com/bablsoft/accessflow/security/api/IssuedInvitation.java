package com.bablsoft.accessflow.security.api;

/**
 * Result of {@link UserInvitationService#invite}: the persisted view plus the one-time
 * plaintext token. The token is included only in this struct (so the email helper can use it
 * for the accept URL) and is never returned by any other read API.
 */
public record IssuedInvitation(UserInvitationView invitation, String plaintextToken) {}
