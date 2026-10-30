package com.ravishankar.kiteditor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only "kit book".
 *
 * Save: snapshots your current inventory under a name.
 * Select: shows that kit as a preview, and colours every item by how much of
 * it you already carry. Hover an item for its normal tooltip (enchantments
 * included). Track: closes this screen and shows what is left to collect in
 * the top-left corner. This screen NEVER changes your inventory.
 */
public class KitEditorScreen extends Screen {

    // Overall size of the window, in GUI pixels (vanilla inventory is 176x166).
    private static final int WIDTH = 300;
    private static final int HEIGHT = 186;

    private static final int VISIBLE_ROWS = 5;
    private static final int ROW_PITCH = 15;
    private static final int TOTAL_SLOTS = 41;

    private EditBox kitNameBox;
    private UiButton saveButton;
    private UiButton deleteButton;
    private UiButton trackButton;
    private final List<UiButton> buttons = new ArrayList<>();

    // All text in this screen is drawn at this fraction of normal size.
    private static final float TEXT_SCALE = 0.75f;

    /** A small custom button, so its text can use the same smaller size. */
    private static final class UiButton {
        final int x;
        final int y;
        final int w;
        final int h;
        final Runnable action;
        String label;
        boolean active = true;

        UiButton(int x, int y, int w, int h, String label, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private void drawText(
            GuiGraphicsExtractor graphics,
            String text,
            int x,
            int y,
            int color,
            boolean shadow
    ) {
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
        graphics.text(this.font, text, 0, 0, color, shadow);
        graphics.pose().popMatrix();
    }

    private int textWidth(String text) {
        return (int) Math.ceil(this.font.width(text) * TEXT_SCALE);
    }

    private String fitText(String text, int pixelWidth) {
        return this.font.plainSubstrByWidth(
                text, (int) (pixelWidth / TEXT_SCALE)
        );
    }

    private void drawButton(
            GuiGraphicsExtractor graphics,
            UiButton b,
            int mouseX,
            int mouseY
    ) {
        boolean hover = b.active && b.contains(mouseX, mouseY);

        graphics.fill(
                b.x, b.y, b.x + b.w, b.y + b.h,
                hover ? 0xFFAAAAAA : SLOT_LIGHT
        );
        graphics.fill(
                b.x + 1, b.y + 1, b.x + b.w - 1, b.y + b.h - 1,
                !b.active ? 0xFF2A2A2A : (hover ? 0xFF4A4A4A : SLOT_DARK)
        );

        drawText(
                graphics,
                b.label,
                b.x + (b.w - textWidth(b.label)) / 2,
                b.y + (b.h - 7) / 2,
                b.active ? WHITE : 0xFF888888,
                false
        );
    }
    private List<String> savedKits = new ArrayList<>();
    private String selectedKit = "";
    private String message = "Enter a kit name and save, or pick a kit.";
    private int left;
    private int top;
    private int scrollOffset = 0;

    // The kit being previewed: inventory slot -> item stack.
    private final Map<Integer, ItemStack> previewStacks = new HashMap<>();

    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED_WHITE = 0xFFDDDDDD;
    private static final int PANEL = 0xFF252525;
    private static final int INNER_PANEL = 0xFF181818;
    private static final int BORDER = 0xFF555555;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_LIGHT = 0xFF666666;
    private static final int SELECTED = 0xFF555555;

    private static final int EMPTY_SLOT = 0xFF242424;
    private static final int HAVE_ALL = 0xFF1F5A2B;
    private static final int HAVE_SOME = 0xFF5A511F;
    private static final int HAVE_NONE = 0xFF5A1F1F;

    public KitEditorScreen() {
        super(Component.literal("Kit Editor"));
    }

    // Left edge of the slot grid and of the right-hand column.
    private int gridX() {
        return left + 8;
    }

    private int rightX() {
        return left + 182;
    }

    @Override
    protected void init() {
        // Centred both ways, so it sits in the middle of any window size.
        left = (this.width - WIDTH) / 2;
        top = (this.height - HEIGHT) / 2;

        kitNameBox = new EditBox(
                this.font,
                rightX(),
                top + 16,
                110,
                18,
                Component.literal("Kit name")
        );

        kitNameBox.setMaxLength(40);
        kitNameBox.setValue("My Kit");
        this.addRenderableWidget(kitNameBox);

        buttons.clear();

        saveButton = new UiButton(
                rightX(), top + 37, 110, 14,
                "Save Inventory", this::saveCurrentKit
        );

        deleteButton = new UiButton(
                rightX(), top + 150, 110, 14,
                "Delete Selected", this::deleteSelectedKit
        );

        // Closes the screen and shows the corner list, or (if this kit is
        // already tracked) removes the corner list.
        trackButton = new UiButton(
                gridX(), top + 150, 162, 14,
                "Track on Screen", this::toggleTracking
        );

        buttons.add(saveButton);
        buttons.add(deleteButton);
        buttons.add(trackButton);

        refreshSavedKits();

        // If a kit is being tracked, open with that kit already selected.
        if (selectedKit.isBlank()
                && KitTracker.isTracking()
                && savedKits.contains(KitTracker.trackedName())) {
            selectedKit = KitTracker.trackedName();
            kitNameBox.setValue(selectedKit);
        }

        // Re-open with the same kit previewed (e.g. after a window resize).
        if (!selectedKit.isBlank()) {
            loadPreview(selectedKit);
        }
    }

    // ------------------------------------------------------------------
    // Slot layout (shared by drawing and hover detection)
    // ------------------------------------------------------------------

    private int slotX(int index) {
        if (index >= 9 && index <= 35) {
            return gridX() + ((index - 9) % 9) * 18;
        }
        if (index <= 8) {
            return gridX() + index * 18;
        }
        if (index <= 39) {
            // Armor, helmet first (slot 39) to boots (slot 36)
            return gridX() + (39 - index) * 18;
        }
        return gridX() + 110; // offhand
    }

    private int slotY(int index) {
        if (index >= 9 && index <= 35) {
            return top + 19 + ((index - 9) / 9) * 18;
        }
        if (index <= 8) {
            return top + 77;
        }
        return top + 110; // armor and offhand
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    // The game draws its background first, then we draw our panels on top.
    // Buttons and the text box are drawn afterwards, above everything here.
    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    ) {
        super.extractBackground(graphics, mouseX, mouseY, delta);

        // Keep the track button's label and state up to date.
        if (trackButton != null) {
            trackButton.active =
                    !selectedKit.isBlank() && !previewStacks.isEmpty();

            trackButton.label = KitTracker.isTracking(selectedKit)
                            ? "Stop Tracking"
                            : "Track on Screen";
        }

        drawPanels(graphics, mouseX, mouseY);
    }

    private void drawPanels(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY
    ) {
        int gx = gridX();
        int rx = rightX();

        // Main window
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, BORDER);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + HEIGHT - 1, PANEL);

        // Kit preview backing
        graphics.fill(gx - 3, top + 16, gx + 165, top + 131, BORDER);
        graphics.fill(gx - 2, top + 17, gx + 164, top + 130, INNER_PANEL);

        Minecraft client = Minecraft.getInstance();
        int[] owned = KitTracker.computeOwned(client, previewStacks);

        String heading = selectedKit.isBlank()
                ? "KIT PREVIEW"
                : "KIT: " + selectedKit;

        if (textWidth(heading) > 100) {
            heading = fitText(heading, 94) + "...";
        }

        drawText(graphics, heading, gx, top + 6, WHITE, true);

        if (!previewStacks.isEmpty()) {
            int complete = 0;
            int total = 0;

            for (Map.Entry<Integer, ItemStack> entry
                    : previewStacks.entrySet()) {
                total++;
                if (owned[entry.getKey()] >= entry.getValue().getCount()) {
                    complete++;
                }
            }

            String summary = complete + "/" + total + " ready";

            drawText(graphics, summary,
                    gx + 162 - textWidth(summary),
                    top + 6,
                    complete == total ? 0xFF55FF55 : MUTED_WHITE,
                    false
            );
        }

        // Section labels
        drawText(graphics, "ARMOR", gx, top + 100, MUTED_WHITE, false);
        drawText(graphics, "OFFHAND", gx + 110, top + 100, MUTED_WHITE, false);

        ItemStack hovered = null;
        int hoveredSlot = -1;

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            int x = slotX(slot);
            int y = slotY(slot);

            ItemStack stack = previewStacks.get(slot);
            boolean hasItem = stack != null && !stack.isEmpty();

            int background = EMPTY_SLOT;

            if (hasItem) {
                if (owned[slot] >= stack.getCount()) {
                    background = HAVE_ALL;
                } else if (owned[slot] > 0) {
                    background = HAVE_SOME;
                } else {
                    background = HAVE_NONE;
                }
            }

            graphics.fill(x, y, x + 18, y + 18, SLOT_LIGHT);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, background);

            if (hasItem) {
                graphics.item(stack, x + 1, y + 1);
                graphics.itemDecorations(this.font, stack, x + 1, y + 1);

                if (mouseX >= x + 1 && mouseX < x + 17
                        && mouseY >= y + 1 && mouseY < y + 17) {
                    hovered = stack;
                    hoveredSlot = slot;
                }
            }
        }

        if (previewStacks.isEmpty()) {
            String hint = "Select a kit to preview it";

            drawText(graphics, hint,
                    gx + 81 - textWidth(hint) / 2,
                    top + 60,
                    MUTED_WHITE,
                    false
            );
        }

        // Colour legend
        drawLegend(graphics, gx, top + 137, HAVE_ALL, "Have all");
        drawLegend(graphics, gx + 56, top + 137, HAVE_SOME, "Some");
        drawLegend(graphics, gx + 104, top + 137, HAVE_NONE, "None");

        // Right column labels
        drawText(graphics, "Kit name", rx, top + 6, WHITE, false);

        String listLabel = savedKits.size() > VISIBLE_ROWS
                ? "SAVED KITS (" + savedKits.size() + ")"
                : "SAVED KITS";

        drawText(graphics, listLabel, rx, top + 58, WHITE, true);

        // Saved kits list
        graphics.fill(rx - 1, top + 68, rx + 111, top + 147, BORDER);
        graphics.fill(rx, top + 69, rx + 110, top + 146, INNER_PANEL);

        if (savedKits.isEmpty()) {

            drawText(graphics, "No saved kits", rx + 5, top + 74, MUTED_WHITE, false
            );

        } else {

            int rows = Math.min(savedKits.size() - scrollOffset, VISIBLE_ROWS);

            for (int i = 0; i < rows; i++) {
                int y = top + 70 + i * ROW_PITCH;
                String kitName = savedKits.get(scrollOffset + i);

                graphics.fill(rx + 2, y, rx + 108, y + 14, SLOT_LIGHT);
                graphics.fill(
                        rx + 3, y + 1, rx + 107, y + 13,
                        kitName.equals(selectedKit) ? SELECTED : SLOT_DARK
                );

                String shown = textWidth(kitName) > 98
                        ? fitText(kitName, 92) + "..."
                        : kitName;

                drawText(graphics, shown, rx + 6, y + 3, WHITE, false);
            }
        }

        for (UiButton b : buttons) {
            drawButton(graphics, b, mouseX, mouseY);
        }

        // Status bar: hover info if hovering an item, otherwise the message
        String status = message;

        if (hovered != null) {
            status = hovered.getHoverName().getString()
                    + ": you have " + owned[hoveredSlot]
                    + " / " + hovered.getCount();
        }

        graphics.fill(left + 5, top + 168, left + WIDTH - 5, top + 182, INNER_PANEL);

        String shownStatus = textWidth(status) > 282
                ? fitText(status, 276) + "..."
                : status;

        drawText(graphics, shownStatus, left + 9, top + 172, WHITE, false);

        // Item tooltip (name, enchantments, etc.), drawn on top of everything.
        if (hovered != null) {
            graphics.setTooltipForNextFrame(this.font, hovered, mouseX, mouseY);
        }
    }

    private void drawLegend(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int color,
            String label
    ) {
        graphics.fill(x, y, x + 9, y + 9, SLOT_LIGHT);
        graphics.fill(x + 1, y + 1, x + 8, y + 8, color);
        drawText(graphics, label, x + 13, y + 1, MUTED_WHITE, false);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private boolean isOverList(double mouseX, double mouseY) {
        int rx = rightX();

        return mouseX >= rx + 2
                && mouseX <= rx + 108
                && mouseY >= top + 70
                && mouseY < top + 70 + VISIBLE_ROWS * ROW_PITCH;
    }

    @Override
    public boolean mouseClicked(
            MouseButtonEvent event,
            boolean doubleClick
    ) {
        double mouseX = event.x();
        double mouseY = event.y();

        if (event.button() == 0) {
            for (UiButton b : buttons) {
                if (b.active && b.contains(mouseX, mouseY)) {
                    b.action.run();
                    return true;
                }
            }
        }

        if (event.button() == 0 && isOverList(mouseX, mouseY)) {

            int index = scrollOffset + (int) ((mouseY - (top + 70)) / ROW_PITCH);

            if (index >= 0 && index < savedKits.size()) {

                String clicked = savedKits.get(index);

                selectedKit = clicked;
                kitNameBox.setValue(clicked);
                loadPreview(clicked);

                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        if (isOverList(mouseX, mouseY)) {

            int maxOffset = Math.max(0, savedKits.size() - VISIBLE_ROWS);

            if (scrollY > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (scrollY < 0) {
                scrollOffset = Math.min(maxOffset, scrollOffset + 1);
            }

            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // Actions (none of these modify the player's inventory)
    // ------------------------------------------------------------------

    private void toggleTracking() {
        if (selectedKit.isBlank() || previewStacks.isEmpty()) {
            message = "Select a kit first.";
            return;
        }

        if (KitTracker.isTracking(selectedKit)) {
            // Same button again: remove the corner list and close.
            KitTracker.stop();
        } else {
            KitTracker.start(selectedKit, previewStacks);
        }

        this.onClose();
    }

    private void saveCurrentKit() {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null || client.level == null) {
            message = "Enter a world first.";
            return;
        }

        try {
            RegistryAccess registries = client.level.registryAccess();

            List<KitStorage.KitItem> items = new ArrayList<>();

            for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
                ItemStack stack = client.player.getInventory().getItem(slot);

                if (!stack.isEmpty()) {
                    String data = KitStorage.encodeStack(stack, registries);

                    items.add(new KitStorage.KitItem(slot, data));
                }
            }

            String name = kitNameBox.getValue().trim();

            boolean existed = savedKits.contains(name);

            KitStorage.saveKit(name, items);

            selectedKit = name;
            refreshSavedKits();
            loadPreview(name);

            // If this kit is being tracked, pick up the new contents.
            if (KitTracker.isTracking(name)) {
                KitTracker.start(name, previewStacks);
            }

            message = existed ? "Kit overwritten!" : "Kit saved!";

        } catch (IOException | RuntimeException exception) {
            message = "Save failed: " + exception.getMessage();
        }
    }

    /** Reads a kit from disk and shows it in the preview. Read-only. */
    private void loadPreview(String name) {
        Minecraft client = Minecraft.getInstance();

        previewStacks.clear();

        if (client.level == null) {
            message = "Enter a world first.";
            return;
        }

        try {
            List<KitStorage.KitItem> items = KitStorage.loadKit(name);

            RegistryAccess registries = client.level.registryAccess();

            Map<Integer, ItemStack> decoded = new HashMap<>();

            for (KitStorage.KitItem item : items) {
                decoded.put(
                        item.slot(),
                        KitStorage.decodeStack(item.stackData(), registries)
                );
            }

            previewStacks.putAll(decoded);
            message = "Showing kit: " + name;

        } catch (IOException | RuntimeException exception) {
            message = "Could not open kit: " + exception.getMessage();
        }
    }

    private void deleteSelectedKit() {
        if (selectedKit.isBlank()) {
            message = "Select a kit to delete.";
            return;
        }

        try {
            KitStorage.deleteKit(selectedKit);

            String deletedName = selectedKit;

            // Don't keep tracking a kit that no longer exists.
            if (KitTracker.isTracking(deletedName)) {
                KitTracker.stop();
            }

            selectedKit = "";
            previewStacks.clear();

            refreshSavedKits();

            message = "Deleted: " + deletedName;

        } catch (IOException | RuntimeException exception) {
            message = "Delete failed: " + exception.getMessage();
        }
    }

    private void refreshSavedKits() {
        try {
            savedKits = KitStorage.listKits();

            if (!savedKits.contains(selectedKit)) {
                selectedKit = "";
                previewStacks.clear();
            }

            int maxOffset = Math.max(0, savedKits.size() - VISIBLE_ROWS);
            scrollOffset = Math.min(scrollOffset, maxOffset);

        } catch (IOException exception) {
            savedKits = new ArrayList<>();
            scrollOffset = 0;
            message = "Could not read saved kits.";
        }
    }
}
