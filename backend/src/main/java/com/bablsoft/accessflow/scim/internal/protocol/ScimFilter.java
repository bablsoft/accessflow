package com.bablsoft.accessflow.scim.internal.protocol;

/** A parsed equality filter: {@code attribute} lowercased, sub-attribute paths preserved. */
public record ScimFilter(String attribute, String value) {
}
