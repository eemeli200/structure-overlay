package dev.xaeroaddons.structureoverlay.data;

/**
 * Axis-aligned block footprint of a structure start, in block coordinates (inclusive on
 * both ends), flattened to the map's 2D view by ignoring Y.
 *
 * <p>Extracted from the {@code BB} int arrays that every child piece writes in
 * {@code structures -> starts -> <id> -> Children} (verified against both classic
 * pieces such as mineshafts and jigsaw pieces such as ancient cities / trial
 * chambers, which is what virtually all modded structures use).
 */
public record BlockBox(int x1, int z1, int x2, int z2) {
    /** Normalises the coordinates so x1/x2 and z1/z2 always read min/max. */
    public BlockBox {
        if (x1 > x2) {
            int t = x1;
            x1 = x2;
            x2 = t;
        }
        if (z1 > z2) {
            int t = z1;
            z1 = z2;
            z2 = t;
        }
    }

    /** Smallest box containing both boxes. */
    public static BlockBox union(BlockBox a, BlockBox b) {
        return new BlockBox(
            Math.min(a.x1, b.x1), Math.min(a.z1, b.z1),
            Math.max(a.x2, b.x2), Math.max(a.z2, b.z2));
    }

    /** Width in blocks, inclusive. */
    public int width() {
        return x2 - x1 + 1;
    }

    /** Depth in blocks, inclusive. */
    public int depth() {
        return z2 - z1 + 1;
    }
}
