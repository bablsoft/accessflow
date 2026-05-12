package com.bablsoft.accessflow.core.api;

public interface CredentialEncryptionService {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
