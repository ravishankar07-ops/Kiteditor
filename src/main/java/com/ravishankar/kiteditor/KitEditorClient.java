package com.ravishankar.kiteditor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class KitEditorClient implements ClientModInitializer {

    private static KeyMapping openKitEditorKey;

    @Override
    public void onInitializeClient() {
        // Shows up in Options > Controls > Key Binds, so players can rebind it.
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("kiteditor", "main")
        );

        openKitEditorKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.kiteditor.open",
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        category
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKitEditorKey.consumeClick()) {
                // Only open the editor while in a world.
                if (client.player != null && client.level != null) {
                    client.setScreen(new KitEditorScreen());
                }
            }
        });

        // Small "items left to collect" list in the top-left corner.
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("kiteditor", "kit_tracker"),
                KitTracker::render
        );
    }
}
