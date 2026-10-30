package com.ravishankar.kiteditor;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public final class KitStorage {

    // <game folder>/config/kiteditor/kits
    private static final Path KIT_FOLDER = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("kiteditor")
            .resolve("kits");

    private static final Pattern VALID_NAME =
            Pattern.compile("[a-zA-Z0-9 _-]{1,40}");

    private static final String FORMAT_VERSION = "KIT_EDITOR_V2";

    // Slots 0-8 hotbar, 9-35 inventory, 36-39 armor, 40 offhand.
    private static final int TOTAL_SLOTS = 41;

    private KitStorage() {
    }

    public record KitItem(int slot, String stackData) {
    }

    private static Path getKitPath(String name) {
        if (name == null) {
            throw new IllegalArgumentException(
                    "Kit name cannot be empty."
            );
        }

        String trimmedName = name.trim();

        if (trimmedName.isEmpty()
                || !VALID_NAME.matcher(trimmedName).matches()) {
            throw new IllegalArgumentException(
                    "Use letters, numbers, spaces, _ or - (max 40 characters)."
            );
        }

        // Spaces stay as spaces, so "my kit" and "my_kit" are different kits.
        return KIT_FOLDER.resolve(trimmedName + ".kit");
    }

    public static String encodeStack(
            ItemStack stack,
            RegistryAccess registries
    ) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot save an empty item stack."
            );
        }

        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);

        JsonElement json = ItemStack.CODEC
                .encodeStart(ops, stack)
                .getOrThrow();

        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

        return Base64.getEncoder().encodeToString(bytes);
    }

    public static ItemStack decodeStack(
            String stackData,
            RegistryAccess registries
    ) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(stackData);

            String jsonText = new String(bytes, StandardCharsets.UTF_8);

            JsonElement json = JsonParser.parseString(jsonText);

            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);

            return ItemStack.CODEC
                    .parse(ops, json)
                    .getOrThrow();

        } catch (Exception exception) {
            throw new IOException(
                    "Could not decode saved item data.",
                    exception
            );
        }
    }

    public static void saveKit(
            String name,
            List<KitItem> items
    ) throws IOException {

        // Validate the name before touching the disk.
        Path path = getKitPath(name);

        Files.createDirectories(KIT_FOLDER);

        List<String> lines = new ArrayList<>();
        boolean[] usedSlots = new boolean[TOTAL_SLOTS];

        lines.add("# Kit Editor inventory kit");
        lines.add(FORMAT_VERSION);

        for (KitItem item : items) {

            if (item.slot() < 0 || item.slot() >= TOTAL_SLOTS) {
                throw new IllegalArgumentException(
                        "Invalid inventory slot: " + item.slot()
                );
            }

            if (usedSlots[item.slot()]) {
                throw new IllegalArgumentException(
                        "Duplicate inventory slot: " + item.slot()
                );
            }

            if (item.stackData() == null || item.stackData().isBlank()) {
                throw new IllegalArgumentException(
                        "Missing item data for slot " + item.slot()
                );
            }

            try {
                Base64.getDecoder().decode(item.stackData());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Invalid item data for slot " + item.slot(),
                        exception
                );
            }

            usedSlots[item.slot()] = true;

            lines.add(item.slot() + "|" + item.stackData());
        }

        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static List<KitItem> loadKit(String name) throws IOException {

        Path path = getKitPath(name);

        if (!Files.isRegularFile(path)) {
            throw new IOException("Kit not found: " + name);
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        boolean versionFound = lines.stream()
                .anyMatch(line -> line.equals(FORMAT_VERSION));

        if (!versionFound) {
            throw new IOException(
                    "This kit uses the old format. Please save it again."
            );
        }

        List<KitItem> items = new ArrayList<>();
        boolean[] usedSlots = new boolean[TOTAL_SLOTS];

        for (String line : lines) {

            if (line.isBlank()
                    || line.startsWith("#")
                    || line.equals(FORMAT_VERSION)) {
                continue;
            }

            String[] parts = line.split("\\|", 2);

            if (parts.length != 2) {
                throw new IOException("Invalid kit file format.");
            }

            try {
                int slot = Integer.parseInt(parts[0]);
                String stackData = parts[1];

                if (slot < 0
                        || slot >= TOTAL_SLOTS
                        || usedSlots[slot]
                        || stackData.isBlank()) {
                    throw new IOException(
                            "Invalid kit item data for slot " + slot
                    );
                }

                Base64.getDecoder().decode(stackData);

                usedSlots[slot] = true;

                items.add(new KitItem(slot, stackData));

            } catch (NumberFormatException exception) {
                throw new IOException(
                        "Invalid inventory slot number.",
                        exception
                );
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Invalid saved item data.",
                        exception
                );
            }
        }

        return items;
    }

    public static List<String> listKits() throws IOException {

        Files.createDirectories(KIT_FOLDER);

        List<String> names = new ArrayList<>();

        try (var files = Files.list(KIT_FOLDER)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".kit"))
                    .forEach(path -> {
                        String filename = path.getFileName().toString();

                        names.add(
                                filename.substring(0, filename.length() - 4)
                        );
                    });
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);

        return names;
    }

    public static void deleteKit(String name) throws IOException {

        Path path = getKitPath(name);

        if (!Files.isRegularFile(path)) {
            throw new IOException("Kit not found: " + name);
        }

        Files.delete(path);
    }
}
