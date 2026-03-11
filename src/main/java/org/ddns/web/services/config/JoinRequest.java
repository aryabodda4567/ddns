package org.ddns.web.services.config;

/**
 * Request payload for /join.
 * The node's keypair is auto-generated server-side; no privateKey field is
 * needed.
 */
public class JoinRequest {
    public String bootstrapIp;
    public String username;
    public String password;
    public String bootstrapPublicKey;
}
