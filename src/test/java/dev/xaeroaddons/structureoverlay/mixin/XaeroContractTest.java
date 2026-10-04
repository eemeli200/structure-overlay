package dev.xaeroaddons.structureoverlay.mixin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Guards the contract this addon depends on inside Xaero's World Map jar.
 *
 * <p>Every mixin target, shadowed member and element-renderer hook used by the mod is
 * asserted against the real dependency jar, so an incompatible Xaero update fails the
 * build instead of failing at runtime inside the game.
 */
class XaeroContractTest {
    private static final String GUI_MAP = "xaero/map/gui/GuiMap";
    private static final String WORLD_MAP = "xaero/map/WorldMap";
    private static final String HANDLER = "xaero/map/element/MapElementRenderHandler";
    private static final String RENDERER = "xaero/map/element/MapElementRenderer";
    private static final String RENDERER_BASE = "xaero/map/element/render/ElementRenderer";
    private static final String READER = "xaero/map/element/MapElementReader";
    private static final String READER_BASE = "xaero/map/element/render/ElementReader";
    private static final String PROVIDER = "xaero/map/element/MapElementRenderProvider";
    private static final String RENDER_LOCATION = "xaero/map/element/render/ElementRenderLocation";

    private static Path xaeroJar;

    @BeforeAll
    static void locateJar() {
        String property = System.getProperty("structureoverlay.xaeroJar", "");
        Assumptions.assumeFalse(property.isEmpty(), "Xaero jar path not provided to the test JVM");
        xaeroJar = Path.of(property);
        Assumptions.assumeTrue(Files.isRegularFile(xaeroJar), "Xaero jar not found: " + xaeroJar);
    }

    // ------------------------------------------------------------------ mixin

    @Test
    void guiMapDeclaresInitNoArgInjectionTarget() throws Exception {
        ClassNode node = read(GUI_MAP);
        assertTrue(method(node, "init", "()V").isPresent(),
            "GuiMap.init()V must exist - GuiMapMixin injects into it");
    }

    @Test
    void guiMapDeclaresShadowedAddButton() throws Exception {
        ClassNode node = read(GUI_MAP);
        String expected = "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
            + "Lnet/minecraft/client/gui/components/events/GuiEventListener;";
        assertTrue(method(node, "addButton", expected).isPresent(),
            "GuiMap.addButton(T)T shadow signature changed");
    }

    // ------------------------------------------------------- element framework

    @Test
    void worldMapExposesPublicElementHandlerField() throws Exception {
        ClassNode node = read(WORLD_MAP);
        Optional<FieldNode> field = node.fields.stream()
            .filter(f -> f.name.equals("mapElementRenderHandler"))
            .findFirst();
        assertTrue(field.isPresent(), "WorldMap.mapElementRenderHandler must exist");
        assertTrue((field.get().access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0
                && (field.get().access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0,
            "WorldMap.mapElementRenderHandler must stay public static");
    }

    @Test
    void handlerExposesAdd() throws Exception {
        ClassNode node = read(HANDLER);
        assertTrue(method(node, "add",
                "(Lxaero/map/element/render/ElementRenderer;)V").isPresent(),
            "MapElementRenderHandler.add must exist");
    }

    @Test
    void rendererHooksWeImplementStillExist() throws Exception {
        ClassNode node = read(RENDERER);
        // the abstract methods StructureElementRenderer implements
        assertTrue(method(node, "beforeRender", null).isPresent(), "beforeRender missing");
        assertTrue(method(node, "afterRender", null).isPresent(), "afterRender missing");
        assertTrue(method(node, "renderElementPre", null).isPresent(), "renderElementPre missing");
        assertTrue(method(node, "shouldRender", "(IZ)Z").isPresent(), "shouldRender(int,boolean) missing");
        // and the modern entry points we override
        assertTrue(method(node, "preRender", null).isPresent(), "preRender missing");
        assertTrue(method(node, "renderElement", null).isPresent(), "renderElement missing");

        ClassNode base = read(RENDERER_BASE);
        assertTrue(method(base, "getOrder", "()I").isPresent(), "ElementRenderer.getOrder missing");
        assertTrue(method(base, "shouldBeDimScaled", "()Z").isPresent(),
            "ElementRenderer.shouldBeDimScaled missing");
        assertTrue(method(base, "shouldRender", null).isPresent(),
            "ElementRenderer.shouldRender missing");
    }

    @Test
    void readerAndProviderContractsExist() throws Exception {
        ClassNode reader = read(READER);
        assertTrue(method(reader, "isInteractable", null).isPresent(), "MapElementReader.isInteractable missing");

        ClassNode readerBase = read(READER_BASE);
        assertTrue(method(readerBase, "shouldScaleBoxWithOptionalScale", "()Z").isPresent(),
            "ElementReader.shouldScaleBoxWithOptionalScale missing");
        assertTrue(method(readerBase, "getTooltip", null).isPresent(), "ElementReader.getTooltip missing");
        assertTrue(method(readerBase, "isHidden", null).isPresent(), "ElementReader.isHidden missing");
        assertTrue(method(readerBase, "getRenderX", null).isPresent(), "ElementReader.getRenderX missing");

        ClassNode provider = read(PROVIDER);
        assertTrue(method(provider, "begin", "(ILjava/lang/Object;)V").isPresent()
                || method(provider, "begin", "(Lxaero/map/element/render/ElementRenderLocation;Ljava/lang/Object;)V").isPresent(),
            "provider begin missing");

        ClassNode location = read(RENDER_LOCATION);
        assertTrue(method(location, "getIndex", "()I").isPresent(), "ElementRenderLocation.getIndex missing");
        assertTrue(method(location, "fromIndex", "(I)Lxaero/map/element/render/ElementRenderLocation;").isPresent(),
            "ElementRenderLocation.fromIndex missing");
    }

    // ------------------------------------------------------------------ utils

    private static ClassNode read(String internalName) throws IOException {
        try (ZipFile zip = new ZipFile(xaeroJar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(internalName + ".class");
            assertNotNull(entry, "Missing class " + internalName + " in " + xaeroJar);
            try (InputStream in = zip.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, 0);
                return node;
            }
        }
    }

    private static Optional<MethodNode> method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
            .filter(m -> m.name.equals(name) && (descriptor == null || descriptor.equals(m.desc)))
            .findFirst();
    }
}
