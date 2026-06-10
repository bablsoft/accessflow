package com.bablsoft.accessflow.core.api;

/**
 * Narrow decrypt-only capability handed to a {@link QueryEngine} via its
 * {@link QueryEngineContext} — engines receive {@link
 * DatasourceConnectionDescriptor#passwordEncrypted()} ciphertext and decrypt it only at native
 * client construction time, mirroring the host rule that plaintext credentials exist in memory no
 * longer than pool initialization. Backed by the host's {@link CredentialEncryptionService};
 * deliberately not the full service, so plugins cannot encrypt or re-wrap secrets.
 */
@FunctionalInterface
public interface CredentialDecryptor {

    /** Decrypt an AES-256-GCM ciphertext produced by the host's credential encryption. */
    String decrypt(String ciphertext);
}
