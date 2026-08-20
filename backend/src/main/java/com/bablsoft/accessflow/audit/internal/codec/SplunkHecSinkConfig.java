package com.bablsoft.accessflow.audit.internal.codec;

import java.net.URI;

/** Decrypted Splunk HEC sink settings for dispatch. Never expose outside the audit module. */
public record SplunkHecSinkConfig(URI url, String tokenPlain, String index, String source) {

    public static final String DEFAULT_SOURCE = "accessflow";
}
