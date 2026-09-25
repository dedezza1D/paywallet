package br.com.paywallet.user;

public enum UserType {
    /** Individual (CPF): sends and receives money. */
    COMMON,
    /** Merchant (CNPJ): only receives money. */
    MERCHANT;

    public boolean canSendMoney() {
        return this == COMMON;
    }
}
