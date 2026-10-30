package com.ravishankar.kiteditor;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers which kit the player is tracking and draws a small, transparent
 * "items left to collect" list in the top-left corner of the screen.
 * Read-only: it never changes the player's inventory.
 */
public final class KitTracker {

    private static final int TOTAL_SLOTS = 41;
    private static final int MAX_LINES = 6;
    private static final float TEXT_SCALE = 0.75f;

    private static String trackedName = null;
    private static final Map<Integer, ItemStack> wanted = new HashMap<>();

    private KitTracker() {
    }

    public record Missing(ItemStack stack, int count) {
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public static void start(String name, Map<Integer, ItemStack> stacks) {
        wanted.clear();

        for (Map.Entry<Integer, ItemStack> entry : stacks.entrySet()) {
            wanted.put(entry.getKey(), entry.getValue().copy());
        }

        trackedName = name;
    }

    public static void stop() {
        trackedName = null;
        wanted.clear();
    }

    public static boolean isTracking() {
        return trackedName != null;
    }

    public static boolean isTracking(String name) {
        return trackedName != null && trackedName.equals(name);
    }

    public static String trackedName() {
        return trackedName;
    }

    // ------------------------------------------------------------------
    // Comparing a kit against what the player carries
    // ------------------------------------------------------------------

    public static boolean sameItem(ItemStack wantedStack, ItemStack owned) {
        return wantedStack.getItem() == owned.getItem()
                && Objects.equals(
                        wantedStack.get(DataComponents.ENCHANTMENTS),
                        owned.get(DataComponents.ENCHANTMENTS))
                && Objects.equals(
                        wantedStack.get(DataComponents.STORED_ENCHANTMENTS),
                        owned.get(DataComponents.STORED_ENCHANTMENTS));
    }

    /**
     * For each kit slot, how many of the wanted items the player carries.
     * Each carried item is only counted once across the whole kit.
     */
    public static int[] computeOwned(
            Minecraft client,
            Map<Integer, ItemStack> stacks
    ) {
        int[] have = new int[TOTAL_SLOTS];

        if (client.player == null) {
            return have;
        }

        Inventory inventory = client.player.getInventory();

        int[] remaining = new int[TOTAL_SLOTS];
        for (int j = 0; j < TOTAL_SLOTS; j++) {
            remaining[j] = inventory.getItem(j).getCount();
        }

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            ItemStack wantedStack = stacks.get(slot);

            if (wantedStack == null || wantedStack.isEmpty()) {
                continue;
            }

            int needed = wantedStack.getCount();

            for (int j = 0; j < TOTAL_SLOTS && have[slot] < needed; j++) {
                if (remaining[j] <= 0) {
                    continue;
                }

                if (sameItem(wantedStack, inventory.getItem(j))) {
                    int take = Math.min(needed - have[slot], remaining[j]);
                    remaining[j] -= take;
                    have[slot] += take;
                }
            }
        }

        return have;
    }

    /** What is still missing, with identical items merged together. */
    public static List<Missing> computeMissing(Minecraft client) {
        int[] have = computeOwned(client, wanted);

        List<Missing> result = new ArrayList<>();

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            ItemStack stack = wanted.get(slot);

            if (stack == null || stack.isEmpty()) {
                continue;
            }

            int missing = stack.getCount() - have[slot];

            if (missing <= 0) {
                continue;
            }

            boolean merged = false;

            for (int i = 0; i < result.size(); i++) {
                Missing existing = result.get(i);

                if (sameItem(existing.stack(), stack)) {
                    result.set(
                            i,
                            new Missing(
                                    existing.stack(),
                                    existing.count() + missing
                            )
                    );
                    merged = true;
                    break;
                }
            }

            if (!merged) {
                result.add(new Missing(stack, missing));
            }
        }

        return result;
    }

    private static boolean hasEnchantments(ItemStack stack) {
        ItemEnchantments enchantments =
                stack.get(DataComponents.ENCHANTMENTS);
        ItemEnchantments stored =
                stack.get(DataComponents.STORED_ENCHANTMENTS);

        return (enchantments != null && !enchantments.isEmpty())
                || (stored != null && !stored.isEmpty());
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------

    public static void render(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        if (trackedName == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();

        if (client.player == null) {
            return;
        }

        Font font = client.font;

        List<Missing> missing = computeMissing(client);

        int totalLeft = 0;
        for (Missing entry : missing) {
            totalLeft += entry.count();
        }

        List<String> lines = new ArrayList<>();

        lines.add(
                missing.isEmpty()
                        ? trackedName + ": all collected!"
                        : trackedName + " - " + totalLeft + " left"
        );

        int shown = Math.min(missing.size(), MAX_LINES);

        for (int i = 0; i < shown; i++) {
            Missing entry = missing.get(i);

            String itemName = entry.stack().getHoverName().getString();

            lines.add(
                    entry.count() + "x " + itemName
                            + (hasEnchantments(entry.stack()) ? " *" : "")
            );
        }

        if (missing.size() > MAX_LINES) {
            lines.add("+" + (missing.size() - MAX_LINES) + " more...");
        }

        int width = 0;
        for (String line : lines) {
            width = Math.max(
                    width, (int) Math.ceil(font.width(line) * TEXT_SCALE)
            );
        }

        int x = 4;
        int y = 4;
        int lineHeight = 8;

        // Very light background so the text stays readable but unobtrusive.
        graphics.fill(
                x - 2, y - 2,
                x + width + 2, y + lines.size() * lineHeight,
                0x40000000
        );

        for (int i = 0; i < lines.size(); i++) {
            int color;

            if (i == 0) {
                color = missing.isEmpty() ? 0xB055FF55 : 0xB0FFFFFF;
            } else {
                color = 0x90FFFFFF;
            }

            graphics.pose().pushMatrix();
            graphics.pose().translate((float) x, (float) (y + i * lineHeight));
            graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
            graphics.text(font, lines.get(i), 0, 0, color, true);
            graphics.pose().popMatrix();
        }
    }
}
