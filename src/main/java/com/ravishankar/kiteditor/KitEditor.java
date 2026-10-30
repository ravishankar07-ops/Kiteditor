
package com.ravishankar.kiteditor;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KitEditor implements ModInitializer {
    public static final String MOD_ID = "kiteditor";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Kit Editor has loaded successfully!");
    }

    public static Identifier id(@NonNull String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
