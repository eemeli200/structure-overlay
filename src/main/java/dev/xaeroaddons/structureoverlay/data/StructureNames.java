package dev.xaeroaddons.structureoverlay.data;

/**
 * Turns structure ids into display names.
 *
 * <p>Vanilla 1.21.1 ships no translation keys for structure ids (verified against the
 * game's {@code en_us.json}), so ids are humanised directly: {@code minecraft:village_plains}
 * becomes {@code Village Plains}.
 */
public final class StructureNames {
    private StructureNames() {
    }

    public static String displayName(String structureId) {
        int namespaceEnd = structureId.indexOf(':');
        String path = namespaceEnd >= 0 ? structureId.substring(namespaceEnd + 1) : structureId;
        StringBuilder out = new StringBuilder(path.length() + 4);
        for (String word : path.split("[_./]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? structureId : out.toString();
    }

    /** Short form for menu buttons: drops the {@code minecraft:} namespace. */
    public static String shortName(String structureId) {
        return structureId.startsWith("minecraft:") ? structureId.substring("minecraft:".length()) : structureId;
    }
}
