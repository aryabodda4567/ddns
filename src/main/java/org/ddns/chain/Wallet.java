package org.ddns.chain;

import org.ddns.bc.SignatureUtil;

import java.security.KeyPair;

/**
 * A simple class to hold and manage a node's cryptographic identity (key pair).
 */
public class Wallet {
    private final KeyPair keyPair;

    public Wallet() {
        // In a real application, you would load this from a file.
        // For simplicity, we generate a new one each time the node starts.
        this.keyPair = SignatureUtil.generateKeyPair();
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }
}
