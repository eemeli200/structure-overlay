package dev.xaeroaddons.structureoverlay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xaeroaddons.structureoverlay.data.StructureColors;
import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persisted user choices: master overlay toggle, label toggle and the set of structure
 * types the user switched off. Stored as JSON in {@code config/structureoverlay.json}.
 *
 * <p>Newly discovered structure types are shown by default; a type only disappears once
 * the user explicitly disables it (it then lands in {@link #disabledStructures}).
 */
public final class OverlayConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static OverlayConfig instance;

    /** Master switch for the whole overlay. */
    public boolean enabled = true;
    /** Draw the structure name under every marker (otherwise only on hover). */
    public boolean showNames = true;
    /** Draw the outline of each structure's footprint. */
    public boolean boundsOutline = true;
    /** Draw the translucent fill of each structure's footprint. */
    public boolean boundsFill = true;
    /** Structure types the user turned off. */
    public Set<String> disabledStructures = new HashSet<>();

    /** Menu sort mode: {@link dev.xaeroaddons.structureoverlay.data.StructureQuery#SORT_*}. */
    public int sortMode = 0;
    /** Footprint fill opacity in percent (menu cycles 10/20/30/50). */
    public int fillOpacity = 20;
    /** Marker size multiplier (menu cycles 0.5/1/1.5/2). */
    public float markerScale = 1.0F;
    /** User-picked colours per structure type, {@code id -> #RRGGBB}. */
    public Map<String, String> structureColors = new HashMap<>();

    public static OverlayConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("structureoverlay.json");
    }

    private static OverlayConfig load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return new OverlayConfig();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            OverlayConfig config = GSON.fromJson(reader, OverlayConfig.class);
            if (config != null) {
                config.sanitize();
                return config;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read {}, starting with defaults", file, e);
        }
        return new OverlayConfig();
    }

    private void sanitize() {
        if (disabledStructures == null) {
            disabledStructures = new HashSet<>();
        }
        if (structureColors == null) {
            structureColors = new HashMap<>();
        }
        structureColors.entrySet().removeIf(e -> e.getKey() == null || StructureColors.parseHex(e.getValue()) == null);
        sortMode = Math.floorMod(sortMode, 3);
        fillOpacity = Math.max(1, Math.min(100, fillOpacity));
        markerScale = Math.max(0.25F, Math.min(4.0F, markerScale));
    }

    public void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not save {}", file, e);
        }
    }

    public boolean isStructureEnabled(String structureId) {
        return !disabledStructures.contains(structureId);
    }

    public void setStructureEnabled(String structureId, boolean enabled) {
        if (enabled) {
            disabledStructures.remove(structureId);
        } else {
            disabledStructures.add(structureId);
        }
        save();
        StructureRepository.invalidate();
    }
}
