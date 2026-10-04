package dev.xaeroaddons.structureoverlay.element;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

/**
 * Shared render context. Xaero fills in the dimension currently being displayed on the
 * map before iterating elements, which is what the reader uses to hide markers that
 * belong to another dimension.
 */
public final class StructureElementContext {
    /** The dimension Xaero's map is currently showing. */
    public ResourceKey<Level> dimension;
}
