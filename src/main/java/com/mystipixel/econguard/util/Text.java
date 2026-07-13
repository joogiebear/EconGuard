package com.mystipixel.econguard.util;

import org.bukkit.ChatColor;

import java.text.DecimalFormat;

public final class Text {
    // DecimalFormat is not thread-safe; give each thread its own instance.
    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }

    public static String money(double amount, String symbol) {
        return symbol + MONEY.get().format(amount);
    }
}
