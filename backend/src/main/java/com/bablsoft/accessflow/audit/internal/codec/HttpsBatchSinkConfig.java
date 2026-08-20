package com.bablsoft.accessflow.audit.internal.codec;

import java.net.URI;

/** Decrypted HTTPS batch sink settings for dispatch. Never expose outside the audit module. */
public record HttpsBatchSinkConfig(URI url, String secretPlain) {
}
