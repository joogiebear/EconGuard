package com.mystipixel.econguard.api;

/** Conventional {@link MoneyEvent#source()} labels. Feature plugins may use any string. */
public final class Sources {
    public static final String BANK = "bank";
    public static final String PAY = "pay";
    public static final String AUCTION = "auction";
    public static final String SHOP = "shop";
    public static final String ADMIN = "admin";
    public static final String INTEREST = "interest";
    public static final String OTHER = "other";

    private Sources() {
    }
}
