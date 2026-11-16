package dev.xaeroaddons.structureoverlay.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fair-play settings for the server half, stored in {@code config/structureoverlay-server.json}.
 *
 * <p>A single {@link #preset} picks a profile and every field can be overridden one by one:
 * a {@code null} field keeps the preset's value, so a server that wants one preset but a
 * different distance limit only writes both lines.
 *
 * <ul>
 *   <li>{@code off} - sends no structure data at all; clients are told why.</li>
 *   <li>{@code strict} - only structures within 1,500 blocks, snapped to a 32-block grid,
 *       no footprints, no rescan, at most 400 entries.</li>
 *   <li>{@code balanced} (default) - within 6,000 blocks, snapped to 16 blocks,
 *       footprints, rescan with a 10 minute cooldown, at most 2,000 entries.</li>
 *   <li>{@code full} - everything the save contains at exact positions, which is what the
 *       addon did before this config existed.</li>
 * </ul>
 *
 * <p>Only a server reads this file; a client keeps its own {@code structureoverlay.json}.
 */
final class ServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private static volatile ServerConfig instance;

    /** One of {@code off}, {@code strict}, {@code balanced}, {@code full}. */
    public String preset = "balanced";

    // ---------------------------------------------------------------- overrides
    /** Blocks from the player; 0 means unlimited. */
    public Integer maxDistance;
    /** Snap marker positions to this many blocks; 0 keeps exact positions. */
    public Integer snapTo;
    /** Send footprint boxes so clients can draw structure extents. */
    public Boolean footprints;
    /** Honour a client's request to re-scan the save. */
    public Boolean allowRescan;
    /** How often a client may force a re-scan; 0 means no cooldown. */
    public Integer rescanCooldownSeconds;
    /** Upper bound on entries sent per player; 0 means unlimited. */
    public Integer maxStructures;
    /** Structure ids that are never sent, e.g. {@code minecraft:ancient_city}. */
    public Set<String> disabledTypes = new HashSet<>();

    /** The settings actually used, after the preset and overrides are combined. */
    record Policy(boolean enabled, int maxDistance, int snapTo, boolean footprints,
                  boolean allowRescan, int rescanCooldownSeconds, int maxStructures,
                  Set<String> disabledTypes) {
        boolean limitedByDistance() {
            return maxDistance > 0;
        }
    }

    private record Preset(boolean enabled, int maxDistance, int snapTo, boolean footprints,
                          boolean allowRescan, int rescanCooldownSeconds, int maxStructures) {
        static Preset parse(String name) {
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "off" -> new Preset(false, 0, 0, false, false, 0, 0);
                case "strict" -> new Preset(true, 1500, 32, false, false, 0, 400);
                case "full" -> new Preset(true, 0, 0, true, true, 0, 0);
                case "", "balanced" -> new Preset(true, 6000, 16, true, true, 600, 2000);
                default -> {
                    LOGGER.warn("Unknown fair-play preset '{}', using 'balanced'", name);
                    yield new Preset(true, 6000, 16, true, true, 600, 2000);
                }
            };
        }
    }

    private static int override(Integer value, int presetValue) {
        return value == null ? presetValue : Math.max(0, value);
    }

    private static boolean override(Boolean value, boolean presetValue) {
        return value == null ? presetValue : value;
    }

    Policy policy() {
        Preset base = Preset.parse(preset);
        return new Policy(
            base.enabled(),
            override(maxDistance, base.maxDistance()),
            override(snapTo, base.snapTo()),
            override(footprints, base.footprints()),
            override(allowRescan, base.allowRescan()),
            override(rescanCooldownSeconds, base.rescanCooldownSeconds()),
            override(maxStructures, base.maxStructures()),
            disabledTypes == null ? Set.of() : Set.copyOf(disabledTypes));
    }

    static ServerConfig get() {
        ServerConfig local = instance;
        if (local == null) {
            synchronized (ServerConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Re-reads the file; called when a server starts so edits take effect on restart. */
    static void reload() {
        synchronized (ServerConfig.class) {
            instance = load();
        }
    }

    static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("structureoverlay-server.json");
    }

    private static ServerConfig load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            ServerConfig defaults = new ServerConfig();
            boolean written = defaults.save();
            if (written) {
                LOGGER.info("Wrote the default fair-play config to {} (preset '{}')", file, defaults.preset);
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ServerConfig config = GSON.fromJson(reader, ServerConfig.class);
            if (config != null) {
                if (config.disabledTypes == null) {
                    config.disabledTypes = new HashSet<>();
                }
                return config;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read {}, using the default 'balanced' preset", file, e);
        }
        return new ServerConfig();
    }

    boolean save() {
        Path file = file();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not write {}", file, e);
            return false;
        }
    }
}
