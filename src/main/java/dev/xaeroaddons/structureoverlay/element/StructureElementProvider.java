package dev.xaeroaddons.structureoverlay.element;

import dev.xaeroaddons.structureoverlay.data.StructureRepository;
import java.util.List;
import xaero.map.element.MapElementRenderProvider;
import xaero.map.element.render.ElementRenderLocation;

/** Feeds the currently visible structure markers for the displayed dimension to Xaero. */
public final class StructureElementProvider
    extends MapElementRenderProvider<StructureElement, StructureElementContext> {

    private List<StructureElement> elements = List.of();
    private int index;

    private void load(StructureElementContext context) {
        elements = context.dimension == null
            ? List.of()
            : StructureRepository.renderable(context.dimension.location());
        index = 0;
    }

    // --- int-based API (abstract on the base class); the location index is irrelevant
    // for us because the list already depends on the render context.

    @Override
    public void begin(int location, StructureElementContext context) {
        load(context);
    }

    @Override
    public boolean hasNext(int location, StructureElementContext context) {
        return index < elements.size();
    }

    @Override
    public StructureElement getNext(int location, StructureElementContext context) {
        return elements.get(index++);
    }

    @Override
    public void end(int location, StructureElementContext context) {
        elements = List.of();
        index = 0;
    }

    // --- typed location API used by MapElementRenderHandler

    @Override
    public void begin(ElementRenderLocation location, StructureElementContext context) {
        begin(location.getIndex(), context);
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, StructureElementContext context) {
        return hasNext(location.getIndex(), context);
    }

    @Override
    public StructureElement getNext(ElementRenderLocation location, StructureElementContext context) {
        return getNext(location.getIndex(), context);
    }

    @Override
    public void end(ElementRenderLocation location, StructureElementContext context) {
        end(location.getIndex(), context);
    }
}
