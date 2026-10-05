package dev.xaeroaddons.structureoverlay.data;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Marker/fill colours for structure types.
 *
 * <p>Without a user override the colour is hashed from the id (stable across launches,
 * and spread over the full hue wheel so neighbouring types in the menu differ). Users can
 * re-roll a type's colour by shift-clicking it in the menu; the chosen hex value is
 * persisted in the config under {@code structureColors}.
 */
public final class StructureColors {
    private StructureColors() {
    }

    /** Full ARGB colour for a structure type: the user override if valid, else the hash. */
    public static int colorFor(String structureId, Map<String, String> overrides) {
        if (overrides != null) {
            String override = overrides.get(structureId);
            if (override != null) {
                Integer parsed = parseHex(override);
                if (parsed != null) {
                    return 0xFF000000 | parsed;
                }
            }
        }
        return hashColor(structureId);
    }

    /** Stable, readable colour derived from the id (lifted towards white for contrast). */
    public static int hashColor(String structureId) {
        int hue = Math.floorMod(structureId.hashCode(), 360);
        float sector = hue / 60.0F;
        int index = (int) sector;
        float fraction = sector - index;
        float r;
        float g;
        float b;
        switch (index % 6) {
            case 0 -> { r = 1; g = fraction; b = 0; }
            case 1 -> { r = 1 - fraction; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = fraction; }
            case 3 -> { r = 0; g = 1 - fraction; b = 1; }
            case 4 -> { r = fraction; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = 1 - fraction; }
        }
        // lift towards white so markers stay visible on dark map areas
        r = r * 0.8F + 0.2F;
        g = g * 0.8F + 0.2F;
        b = b * 0.8F + 0.2F;
        int rgb = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        return 0xFF000000 | rgb;
    }

    /** {@code #RRGGBB} for storage in the config. */
    public static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Parses {@code #RRGGBB} / {@code RRGGBB} (any case).
     *
     * @return the 24-bit RGB value, or {@code null} when the text is not a colour
     */
    public static Integer parseHex(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A fresh pleasant colour for "shift-click to re-roll" in the menu. */
    public static String randomHex() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float hue = random.nextFloat();
        float saturation = random.nextFloat(0.55F, 0.95F);
        float value = random.nextFloat(0.75F, 1.0F);
        int rgb = hsvToRgb(hue, saturation, value);
        return toHex(rgb);
    }

    private static int hsvToRgb(float h, float s, float v) {
        float region = h * 6.0F;
        int index = (int) region;
        float fraction = region - index;
        float p = v * (1.0F - s);
        float q = v * (1.0F - s * fraction);
        float t = v * (1.0F - s * (1.0F - fraction));
        float r;
        float g;
        float b;
        switch (index % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
