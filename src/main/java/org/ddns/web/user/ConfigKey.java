package org.ddns.web.user;

public enum ConfigKey {

    // Login State
    IS_LOGGED_IN("is_logged_in"),
    // User Info
    USERNAME("username"),
    PASSWORD("password"),
    EMAIL("email"),
    FIRSTNAME("first_name"),
    LASTNAME("last_name"),
    SESSION_TOKEN("session_token"),
    SESSION_EXPIRES_AT("session_expires_at");

    private final String key;

    ConfigKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
