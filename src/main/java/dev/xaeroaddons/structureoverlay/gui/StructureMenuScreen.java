package dev.xaeroaddons.structureoverlay.gui;

import dev.xaeroaddons.structureoverlay.config.OverlayConfig;
import dev.xaeroaddons.structureoverlay.data.StructureNames;
import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The menu this addon is built around: one master toggle for the overlay, toggles for
 * the name labels and for the footprint line/fill, and a paginated checklist letting
 * the user pick exactly which structure types are drawn on Xaero's world map.
 *
 * <p>Opening it from inside the map keeps the map as {@code parent}, so closing returns
 * the player to the map instead of the game.
 */
public final class StructureMenuScreen extends Screen {
    private static final int CONTROL_Y = 26;
    private static final int STATUS_Y = 48;
    private static final int GRID_TOP = 62;
    private static final int ROWS = 6;
    private static final int COLS = 3;
    private static final int ROW_HEIGHT = 22;
    private static final int PER_PAGE = ROWS * COLS;

    private final Screen parent;
    private int page;

    private final List<Button> typeButtons = new ArrayList<>();
    private final List<String> typeButtonIds = new ArrayList<>();
    private Button overlayButton;
    private Button namesButton;
    private Button rescanButton;
    private Button outlineButton;
    private Button fillButton;
    private Button previousPageButton;
    private Button nextPageButton;

    /** Set while a widget layout change is requested; applied at the next frame. */
    private boolean rebuildRequested;

    public StructureMenuScreen(Screen parent) {
        super(Component.translatable("structureoverlay.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        typeButtons.clear();
        typeButtonIds.clear();
        OverlayConfig config = OverlayConfig.get();

        overlayButton = addRenderableWidget(Button.builder(overlayLabel(config.enabled), b -> {
                config.enabled = !config.enabled;
                config.save();
                StructureRepository.invalidate();
                overlayButton.setMessage(overlayLabel(config.enabled));
            })
            .bounds(8, CONTROL_Y, 100, 20).build());

        namesButton = addRenderableWidget(Button.builder(namesLabel(config.showNames), b -> {
                config.showNames = !config.showNames;
                config.save();
                namesButton.setMessage(namesLabel(config.showNames));
            })
            .bounds(112, CONTROL_Y, 100, 20).build());

        rescanButton = addRenderableWidget(Button.builder(Component.translatable("structureoverlay.menu.rescan"), b -> {
                StructureRepository.rescan();
                refreshControls();
            })
            .bounds(216, CONTROL_Y, 60, 20).build());

        outlineButton = addRenderableWidget(Button.builder(outlineLabel(config.boundsOutline), b -> {
                config.boundsOutline = !config.boundsOutline;
                config.save();
                outlineButton.setMessage(outlineLabel(config.boundsOutline));
            })
            .bounds(280, CONTROL_Y, 60, 20).build());

        fillButton = addRenderableWidget(Button.builder(fillLabel(config.boundsFill), b -> {
                config.boundsFill = !config.boundsFill;
                config.save();
                fillButton.setMessage(fillLabel(config.boundsFill));
            })
            .bounds(344, CONTROL_Y, 60, 20).build());

        int bottomY = this.height - 26;
        previousPageButton = addRenderableWidget(
            Button.builder(Component.literal("<"), b -> changePage(-1)).bounds(8, bottomY, 20, 20).build());
        nextPageButton = addRenderableWidget(
            Button.builder(Component.literal(">"), b -> changePage(1)).bounds(30, bottomY, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("structureoverlay.menu.all_on"), b -> setAll(true))
            .bounds(58, bottomY, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("structureoverlay.menu.all_off"), b -> setAll(false))
            .bounds(118, bottomY, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("structureoverlay.menu.close"), b -> onClose())
            .bounds(this.width - 56, bottomY, 48, 20).build());

        buildTypeGrid();
        refreshControls();
    }

    private void buildTypeGrid() {
        for (Button button : typeButtons) {
            removeWidget(button);
        }
        typeButtons.clear();
        typeButtonIds.clear();

        List<String> ids = StructureRepository.structureIds();
        if (ids.isEmpty()) {
            return;
        }
        int pageCount = pageCount(ids.size());
        page = Mth.clamp(page, 0, pageCount - 1);
        int cellWidth = (this.width - 16 - (COLS - 1) * 4) / COLS;

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= ids.size()) {
                break;
            }
            String id = ids.get(index);
            Button button = Button.builder(typeLabel(id, cellWidth), b -> toggleType(id, b))
                .bounds(8 + (i % COLS) * (cellWidth + 4), GRID_TOP + (i / COLS) * ROW_HEIGHT, cellWidth, 20)
                .build();
            button.setTooltip(Tooltip.create(Component.literal(
                id + " - " + StructureRepository.countFor(id) + " found")));
            typeButtons.add(addRenderableWidget(button));
            typeButtonIds.add(id);
        }
    }

    private static int pageCount(int itemCount) {
        return Math.max(1, (itemCount + PER_PAGE - 1) / PER_PAGE);
    }

    private void changePage(int delta) {
        int pageCount = pageCount(StructureRepository.structureIds().size());
        int next = Mth.clamp(page + delta, 0, pageCount - 1);
        if (next != page) {
            page = next;
            // Widgets are re-created, so wait for the next frame: mutating the widget
            // list from inside a button callback would race the click dispatch.
            rebuildRequested = true;
        }
    }

    private void toggleType(String id, Button button) {
        OverlayConfig config = OverlayConfig.get();
        config.setStructureEnabled(id, !config.isStructureEnabled(id));
        button.setMessage(typeLabel(id, button.getWidth()));
    }

    private void setAll(boolean enabled) {
        OverlayConfig config = OverlayConfig.get();
        List<String> ids = StructureRepository.structureIds();
        config.disabledStructures.clear();
        if (!enabled) {
            config.disabledStructures.addAll(ids);
        }
        config.save();
        StructureRepository.invalidate();
        for (int i = 0; i < typeButtons.size(); i++) {
            typeButtons.get(i).setMessage(typeLabel(typeButtonIds.get(i), typeButtons.get(i).getWidth()));
        }
    }

    private void refreshControls() {
        if (overlayButton != null) {
            overlayButton.setMessage(overlayLabel(OverlayConfig.get().enabled));
        }
        if (namesButton != null) {
            namesButton.setMessage(namesLabel(OverlayConfig.get().showNames));
        }
        if (outlineButton != null) {
            outlineButton.setMessage(outlineLabel(OverlayConfig.get().boundsOutline));
        }
        if (fillButton != null) {
            fillButton.setMessage(fillLabel(OverlayConfig.get().boundsFill));
        }
        if (rescanButton != null) {
            rescanButton.active = StructureRepository.status() != StructureRepository.Status.SCANNING;
        }
        int pageCount = pageCount(StructureRepository.structureIds().size());
        if (previousPageButton != null) {
            previousPageButton.active = page > 0;
        }
        if (nextPageButton != null) {
            nextPageButton.active = page < pageCount - 1;
        }
    }

    private static Component overlayLabel(boolean enabled) {
        return Component.translatable(enabled ? "structureoverlay.menu.overlay_on" : "structureoverlay.menu.overlay_off");
    }

    private static Component namesLabel(boolean enabled) {
        return Component.translatable(enabled ? "structureoverlay.menu.names_on" : "structureoverlay.menu.names_off");
    }

    private static Component outlineLabel(boolean enabled) {
        return Component.translatable(enabled ? "structureoverlay.menu.line_on" : "structureoverlay.menu.line_off");
    }

    private static Component fillLabel(boolean enabled) {
        return Component.translatable(enabled ? "structureoverlay.menu.fill_on" : "structureoverlay.menu.fill_off");
    }

    private Component typeLabel(String id, int cellWidth) {
        String prefix = OverlayConfig.get().isStructureEnabled(id) ? "[x] " : "[ ] ";
        String name = StructureNames.shortName(id);
        return Component.literal(this.font.plainSubstrByWidth(prefix + name, Math.max(8, cellWidth - 8)));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildWidgets();
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        guiGraphics.drawString(this.font, statusLine(), 8, STATUS_Y, 0xA0A0A0, false);

        List<String> ids = StructureRepository.structureIds();
        if (!ids.isEmpty()) {
            int pageCount = pageCount(ids.size());
            Component pageLabel = Component.translatable("structureoverlay.menu.page", page + 1, pageCount);
            guiGraphics.drawCenteredString(this.font, pageLabel, 190, this.height - 20, 0xA0A0A0);
        }
    }

    private Component statusLine() {
        return switch (StructureRepository.status()) {
            case SCANNING -> {
                int percent = StructureRepository.progressPercent();
                yield Component.translatable("structureoverlay.menu.status.scanning", percent);
            }
            case NO_ACCESS -> Component.translatable("structureoverlay.menu.status.no_access");
            case READY -> StructureRepository.totalStructures() == 0
                ? Component.translatable("structureoverlay.menu.status.empty")
                : Component.translatable("structureoverlay.menu.status.ready",
                    StructureRepository.totalStructures(),
                    StructureRepository.structureTypeCount(),
                    StructureRepository.dimensionCount() > 1
                        ? " in " + StructureRepository.dimensionCount() + " dimensions" : "");
            case IDLE -> StructureRepository.structureIds().isEmpty()
                ? Component.translatable("structureoverlay.menu.status.idle")
                : Component.translatable("structureoverlay.menu.status.empty");
        };
    }

    @Override
    public void onClose() {
        OverlayConfig.get().save();
        this.minecraft.setScreen(parent);
    }
}
