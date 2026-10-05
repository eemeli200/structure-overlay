package dev.xaeroaddons.structureoverlay.gui;

import dev.xaeroaddons.structureoverlay.config.OverlayConfig;
import dev.xaeroaddons.structureoverlay.data.StructureColors;
import dev.xaeroaddons.structureoverlay.data.StructureCsv;
import dev.xaeroaddons.structureoverlay.data.StructureNames;
import dev.xaeroaddons.structureoverlay.data.StructureQuery;
import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import dev.xaeroaddons.structureoverlay.element.StructureElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The menu this addon is built around: one master toggle for the overlay, toggles for the
 * name labels and for the footprint line/fill, search + sorting over the discovered
 * types, and a paginated checklist letting the user pick exactly which structure types
 * are drawn on Xaero's world map.
 *
 * <p>Extra affordances: shift-clicking a type re-rolls its marker colour (ctrl+shift
 * resets it), shift-clicking <em>Rescan</em> forces a full re-read instead of the usual
 * incremental one, and <em>Export</em> writes every discovered structure to a CSV file.
 *
 * <p>Opening it from inside the map keeps the map as {@code parent}, so closing returns
 * the player to the map instead of the game.
 */
public final class StructureMenuScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureMenuScreen.class);

    private static final int CONTROL_Y = 26;
    private static final int TOOLBAR_Y = 48;
    private static final int STATUS_Y = 72;
    private static final int GRID_TOP = 84;
    private static final int ROWS = 5;
    private static final int COLS = 3;
    private static final int ROW_HEIGHT = 22;
    private static final int PER_PAGE = ROWS * COLS;

    /** Fill opacity presets offered by the menu button. */
    private static final int[] OPACITY_STEPS = {10, 20, 30, 50};
    /** Marker size presets offered by the menu button. */
    private static final float[] SIZE_STEPS = {0.5F, 1.0F, 1.5F, 2.0F};

    private final Screen parent;
    private int page;

    /** Contents of the search box; kept so a widget rebuild does not lose the query. */
    private String searchQuery = "";

    private final List<Button> typeButtons = new ArrayList<>();
    private final List<String> typeButtonIds = new ArrayList<>();
    private Button overlayButton;
    private Button namesButton;
    private Button rescanButton;
    private Button outlineButton;
    private Button fillButton;
    private Button sortButton;
    private Button opacityButton;
    private Button sizeButton;
    private EditBox searchBox;
    private Button previousPageButton;
    private Button nextPageButton;

    /** Set while a widget layout change is requested; applied at the next frame. */
    private boolean rebuildRequested;
    /** Set while only the type grid needs re-filtering; applied at the next frame. */
    private boolean gridRebuildRequested;

    /** Transient confirmation shown instead of the status line (e.g. after an export). */
    private Component feedback;
    private long feedbackUntil;

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
            .bounds(8, CONTROL_Y, 96, 20).build());

        namesButton = addRenderableWidget(Button.builder(namesLabel(config.showNames), b -> {
                config.showNames = !config.showNames;
                config.save();
                namesButton.setMessage(namesLabel(config.showNames));
            })
            .bounds(108, CONTROL_Y, 96, 20).build());

        outlineButton = addRenderableWidget(Button.builder(outlineLabel(config.boundsOutline), b -> {
                config.boundsOutline = !config.boundsOutline;
                config.save();
                outlineButton.setMessage(outlineLabel(config.boundsOutline));
            })
            .bounds(208, CONTROL_Y, 64, 20).build());

        fillButton = addRenderableWidget(Button.builder(fillLabel(config.boundsFill), b -> {
                config.boundsFill = !config.boundsFill;
                config.save();
                fillButton.setMessage(fillLabel(config.boundsFill));
            })
            .bounds(276, CONTROL_Y, 64, 20).build());

        rescanButton = addRenderableWidget(Button.builder(Component.translatable("structureoverlay.menu.rescan"), b -> {
                // shift-click: forget the incremental cache and re-read every region file
                if (Screen.hasShiftDown()) {
                    StructureRepository.forceFullRescan();
                } else {
                    StructureRepository.rescan();
                }
                refreshControls();
            })
            .bounds(344, CONTROL_Y, 64, 20).build());
        rescanButton.setTooltip(Tooltip.create(Component.translatable("structureoverlay.menu.rescan.tooltip")));

        searchBox = new EditBox(this.font, 8, TOOLBAR_Y + 2, 118, 16,
            Component.translatable("structureoverlay.menu.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("structureoverlay.menu.search"));
        if (!searchQuery.isEmpty()) {
            searchBox.setValue(searchQuery);
        }
        searchBox.setResponder(text -> {
            searchQuery = text == null ? "" : text;
            page = 0;
            gridRebuildRequested = true;
        });
        addRenderableWidget(searchBox);

        sortButton = addRenderableWidget(Button.builder(sortLabel(config.sortMode), b -> {
                config.sortMode = (config.sortMode + 1) % 3;
                config.save();
                sortButton.setMessage(sortLabel(config.sortMode));
                gridRebuildRequested = true;
            })
            .bounds(130, TOOLBAR_Y, 74, 20).build());
        sortButton.setTooltip(Tooltip.create(Component.translatable("structureoverlay.menu.sort.tooltip")));

        opacityButton = addRenderableWidget(Button.builder(opacityLabel(config.fillOpacity), b -> {
                config.fillOpacity = nextOpacity(config.fillOpacity);
                config.save();
                opacityButton.setMessage(opacityLabel(config.fillOpacity));
            })
            .bounds(208, TOOLBAR_Y, 74, 20).build());
        opacityButton.setTooltip(Tooltip.create(Component.translatable("structureoverlay.menu.opacity.tooltip")));

        sizeButton = addRenderableWidget(Button.builder(sizeLabel(config.markerScale), b -> {
                config.markerScale = nextSize(config.markerScale);
                config.save();
                sizeButton.setMessage(sizeLabel(config.markerScale));
            })
            .bounds(286, TOOLBAR_Y, 58, 20).build());
        sizeButton.setTooltip(Tooltip.create(Component.translatable("structureoverlay.menu.size.tooltip")));

        Button exportButton = addRenderableWidget(Button.builder(
                Component.translatable("structureoverlay.menu.export"), b -> exportCsv())
            .bounds(348, TOOLBAR_Y, 60, 20).build());
        exportButton.setTooltip(Tooltip.create(Component.translatable("structureoverlay.menu.export.tooltip")));

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

    /** Structure types matching the search box, in the current sort order. */
    private List<String> visibleIds() {
        List<String> ids = StructureQuery.filter(StructureRepository.structureIds(), searchQuery);
        return StructureQuery.sort(ids, OverlayConfig.get().sortMode, StructureRepository::countFor);
    }

    private void buildTypeGrid() {
        for (Button button : typeButtons) {
            removeWidget(button);
        }
        typeButtons.clear();
        typeButtonIds.clear();

        List<String> ids = visibleIds();
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
            button.setTooltip(Tooltip.create(typeTooltip(id)));
            typeButtons.add(addRenderableWidget(button));
            typeButtonIds.add(id);
        }
    }

    private static Component typeTooltip(String id) {
        return Component.literal(id + " - " + StructureRepository.countFor(id) + " found")
            .append(" • ").append(Component.translatable("structureoverlay.menu.color_hint"));
    }

    private static int pageCount(int itemCount) {
        return Math.max(1, (itemCount + PER_PAGE - 1) / PER_PAGE);
    }

    private void changePage(int delta) {
        int pageCount = pageCount(visibleIds().size());
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

    /**
     * Shift-clicking a type cell picks a fresh marker colour for it; ctrl+shift-clicking
     * clears the override and returns to the colour derived from the id.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && (Screen.hasShiftDown() || Screen.hasControlDown())) {
            for (int i = 0; i < typeButtons.size(); i++) {
                Button candidate = typeButtons.get(i);
                if (!candidate.isMouseOver(mouseX, mouseY)) {
                    continue;
                }
                OverlayConfig config = OverlayConfig.get();
                String id = typeButtonIds.get(i);
                if (Screen.hasControlDown()) {
                    config.structureColors.remove(id);
                } else {
                    config.structureColors.put(id, StructureColors.randomHex());
                }
                config.save();
                StructureRepository.invalidate();
                candidate.setMessage(typeLabel(id, candidate.getWidth()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

    /** Writes every structure of the scan (not just the visible ones) to a CSV file. */
    private void exportCsv() {
        try {
            Path out = FMLPaths.GAMEDIR.get().resolve("structureoverlay-structures.csv");
            String csv = StructureCsv.render(StructureRepository.currentResult());
            Files.writeString(out, csv, java.nio.charset.StandardCharsets.UTF_8);
            feedback = Component.translatable("structureoverlay.menu.exported",
                StructureRepository.totalStructures(), out.getFileName().toString());
        } catch (Exception e) {
            LOGGER.error("CSV export failed", e);
            feedback = Component.translatable("structureoverlay.menu.export_failed");
        }
        feedbackUntil = System.currentTimeMillis() + 6000L;
    }

    private void refreshControls() {
        OverlayConfig config = OverlayConfig.get();
        if (overlayButton != null) {
            overlayButton.setMessage(overlayLabel(config.enabled));
        }
        if (namesButton != null) {
            namesButton.setMessage(namesLabel(config.showNames));
        }
        if (outlineButton != null) {
            outlineButton.setMessage(outlineLabel(config.boundsOutline));
        }
        if (fillButton != null) {
            fillButton.setMessage(fillLabel(config.boundsFill));
        }
        if (sortButton != null) {
            sortButton.setMessage(sortLabel(config.sortMode));
        }
        if (opacityButton != null) {
            opacityButton.setMessage(opacityLabel(config.fillOpacity));
        }
        if (sizeButton != null) {
            sizeButton.setMessage(sizeLabel(config.markerScale));
        }
        if (rescanButton != null) {
            rescanButton.active = StructureRepository.status() != StructureRepository.Status.SCANNING;
        }
        int pageCount = pageCount(visibleIds().size());
        if (previousPageButton != null) {
            previousPageButton.active = page > 0;
        }
        if (nextPageButton != null) {
            nextPageButton.active = page < pageCount - 1;
        }
    }

    private static int nextOpacity(int current) {
        for (int step : OPACITY_STEPS) {
            if (step > current) {
                return step;
            }
        }
        return OPACITY_STEPS[0];
    }

    private static float nextSize(float current) {
        for (float step : SIZE_STEPS) {
            if (step > current + 0.01F) {
                return step;
            }
        }
        return SIZE_STEPS[0];
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

    private static Component sortLabel(int mode) {
        return switch (mode) {
            case StructureQuery.SORT_COUNT -> Component.translatable("structureoverlay.menu.sort.count");
            case StructureQuery.SORT_MOD -> Component.translatable("structureoverlay.menu.sort.mod");
            default -> Component.translatable("structureoverlay.menu.sort.name");
        };
    }

    private static Component opacityLabel(int opacity) {
        return Component.translatable("structureoverlay.menu.fill_opacity", opacity);
    }

    private static Component sizeLabel(float scale) {
        String text = scale == Math.floor(scale) ? String.valueOf((int) scale) : String.valueOf(scale);
        return Component.translatable("structureoverlay.menu.marker_size", text);
    }

    /** {@code [x] Name} prefixed with a coloured square showing the marker's colour. */
    private Component typeLabel(String id, int cellWidth) {
        OverlayConfig config = OverlayConfig.get();
        String prefix = config.isStructureEnabled(id) ? "[x] " : "[ ] ";
        String plain = prefix + StructureNames.shortName(id);
        String clipped = this.font.plainSubstrByWidth(plain, Math.max(8, cellWidth - 22));

        int color = StructureColors.colorFor(id, config.structureColors);
        MutableComponent label = Component.empty();
        label.append(Component.literal("■ ")
            .withStyle(style -> style.withColor(TextColor.fromRgb(color))));
        label.append(Component.literal(clipped));
        return label;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildWidgets();
        } else if (gridRebuildRequested) {
            // only the type grid depends on the search query / sort order
            gridRebuildRequested = false;
            buildTypeGrid();
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        String nearest = nearestLine();
        Component status = currentStatus();
        String statusText = this.font.plainSubstrByWidth(status.getString(),
            Math.max(40, this.width - 16 - (nearest.isEmpty() ? 0 : this.font.width(nearest) + 8)));
        guiGraphics.drawString(this.font, statusText, 8, STATUS_Y, 0xA0A0A0, false);
        if (!nearest.isEmpty()) {
            guiGraphics.drawString(this.font, nearest, this.width - 8 - this.font.width(nearest), STATUS_Y, 0x80C0FF, false);
        }

        List<String> ids = visibleIds();
        if (ids.isEmpty()) {
            String emptyKey = StructureRepository.structureIds().isEmpty()
                ? "structureoverlay.menu.no_structures"
                : "structureoverlay.menu.no_matches";
            guiGraphics.drawCenteredString(this.font, Component.translatable(emptyKey),
                this.width / 2, GRID_TOP + 4, 0x808080);
        } else {
            int pageCount = pageCount(ids.size());
            Component pageLabel = Component.translatable("structureoverlay.menu.page", page + 1, pageCount);
            int centre = (174 + this.width - 60) / 2;
            guiGraphics.drawCenteredString(this.font, pageLabel, centre, this.height - 20, 0xA0A0A0);
        }
    }

    private Component currentStatus() {
        if (feedback != null && System.currentTimeMillis() < feedbackUntil) {
            return feedback;
        }
        return statusLine();
    }

    /** "Nearest: Village Plains 340m" for the status row, or "" when unavailable. */
    private String nearestLine() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return "";
        }
        if (StructureRepository.status() != StructureRepository.Status.READY) {
            return "";
        }
        StructureElement nearest = StructureRepository.nearest(
            this.minecraft.level.dimension().location(),
            this.minecraft.player.getX(), this.minecraft.player.getZ());
        if (nearest == null) {
            return "";
        }
        double dx = nearest.x() - this.minecraft.player.getX();
        double dz = nearest.z() - this.minecraft.player.getZ();
        return "Nearest: " + nearest.displayName() + " " + Math.round(Math.sqrt(dx * dx + dz * dz)) + "m";
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
