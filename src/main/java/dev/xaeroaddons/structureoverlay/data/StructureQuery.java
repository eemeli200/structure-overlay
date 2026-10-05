package dev.xaeroaddons.structureoverlay.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

/**
 * Filtering and ordering of the structure type list shown in the menu.
 *
 * <p>Kept free of Minecraft classes so it can be unit tested directly and reused by the
 * GUI without touching widget code.
 */
public final class StructureQuery {
    /** Sort by display name (A-Z). */
    public static final int SORT_NAME = 0;
    /** Sort by how many instances were found (most first). */
    public static final int SORT_COUNT = 1;
    /** Group by mod namespace, then by name. */
    public static final int SORT_MOD = 2;

    private StructureQuery() {
    }

    /**
     * Case-insensitive substring filter over the raw id and its humanised display name,
     * so both {@code "village"} and {@code "Village Plains"} match
     * {@code minecraft:village_plains}. A blank query keeps everything.
     *
     * @return a new list preserving the input order
     */
    public static List<String> filter(List<String> ids, String query) {
        if (query == null || query.isBlank()) {
            return List.copyOf(ids);
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id.toLowerCase(Locale.ROOT).contains(needle)
                || StructureNames.displayName(id).toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Sorts a copy of {@code ids} by the given mode ({@link #SORT_NAME}, {@link #SORT_COUNT}
     * or {@link #SORT_MOD}). Display names are compared case-insensitively so
     * {@code ancient_city} does not jump ahead of {@code Village}.
     *
     * @param countOf how many instances of a type exist, used by {@link #SORT_COUNT}
     */
    public static List<String> sort(List<String> ids, int mode, ToLongFunction<String> countOf) {
        List<String> out = new ArrayList<>(ids);
        Comparator<String> byName = Comparator.comparing(
            (String id) -> StructureNames.displayName(id).toLowerCase(Locale.ROOT))
            .thenComparing(Comparator.naturalOrder());
        switch (mode) {
            case SORT_COUNT -> out.sort(Comparator
                .comparingLong(countOf)
                .reversed()
                .thenComparing(byName));
            case SORT_MOD -> out.sort(Comparator
                .comparing(StructureQuery::namespace)
                .thenComparing(byName));
            default -> out.sort(byName);
        }
        return out;
    }

    /** Mod namespace of an id (everything before {@code ':'}), or "" when there is none. */
    public static String namespace(String structureId) {
        int separator = structureId.indexOf(':');
        return separator < 0 ? "" : structureId.substring(0, separator);
    }
}
