package br.com.paywallet.user;

public enum Role {
    CUSTOMER,
    /** Granted only directly in the database, never through the API. */
    ADMIN
}
