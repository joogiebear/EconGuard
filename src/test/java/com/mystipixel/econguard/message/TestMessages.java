package com.mystipixel.econguard.message;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// A MessageManager backed only by the bundled messages.yml, as on a server whose file lacks every key.
public final class TestMessages {
    private TestMessages() {
    }

    public static MessageManager bundled(File dataFolder) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getResource("messages.yml"))
                .thenAnswer(i -> TestMessages.class.getResourceAsStream("/messages.yml"));
        return new MessageManager(plugin);
    }
}
