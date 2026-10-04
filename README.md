# Structure Overlay for Xaero's World Map

A client-side NeoForge addon for **Xaero's World Map** that draws a toggleable overlay of
**structure starts** (villages, monuments, fortresses, ancient cities, ...) on the full
screen map, with a menu for choosing exactly which structure types are shown.

- **Menu** (default key `O`, also a *Structures* button on the Xaero map screen): master
  overlay toggle, label toggle, footprint *Line*/*Fill* toggles, rescan, and a paginated
  checklist of every structure type found in the world.
- **Quick toggle** (default key `P`): turn the whole overlay on/off without opening a menu.
- Markers are colour-coded per structure type, show the structure name (on hover, or
  always via the *Labels* toggle) and coordinates, and are hoverable.
- Every structure's true footprint (the union of its pieces' bounding boxes read from
  the chunk data) is drawn as a translucent fill under the markers plus an outline in the
  type's colour - so big structures like ancient cities read at a glance. Both layers are
  individually toggleable in the menu.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Xaero's World Map | 1.40+ (tested against 1.46.0) |
| XaeroLib | (bundled with Xaero's mods) |

Install this jar alongside NeoForge + Xaero's World Map. Everything runs client side;
the mod does not need to be installed on the server.

## Where the structure data comes from

Positions are read **from the world save on disk** (`region/*.mca`), exactly like vanilla
stores them: every chunk carries a copy of each structure start inside
`structures -> starts`, with the start chunk recorded as `ChunkX`/`ChunkZ`
(`StructureStart.save`). So:

- ✅ Full-map coverage in singleplayer / LAN worlds, no matter how far you have explored.
- ⚠️ On a dedicated server the world save does not exist on your machine, so the overlay
  stays empty (the menu says so).

Scanning happens on a background thread when you join a world, and again via the
**Rescan** button. Results are de-duplicated per dimension and cached until something
changes.

## How the overlay is drawn

The addon plugs into Xaero's own element-rendering pipeline
(`WorldMap.mapElementRenderHandler`), which is the same pipeline used for waypoints and
player markers - so zoom, panning, culling, dimension switching and hover detection all
behave exactly like native Xaero elements. The only mixin is a small injection into
`GuiMap.init()` that adds the *Structures* button to the map screen.

## Building

```bash
gradle build        # or ./gradlew build once a wrapper is generated
```

Produces `build/libs/structureoverlay-1.0.0.jar`.

### Tests

```bash
gradle test
```

- `RegionChunkReaderTest` / `StructureScannerTest` round-trip synthetic `.mca` files
  (zlib/gzip/uncompressed, corrupt chunks, deduplication, multi-dimension layouts).
- `XaeroContractTest` opens the real Xaero's World Map jar and asserts every mixin
  target, shadowed member and element-renderer hook this addon relies on - so an
  incompatible Xaero update fails the build instead of failing in game.

## Default keybinds

| Key | Action |
|---|---|
| `O` | Open the Structure Overlay menu |
| `P` | Toggle the overlay |

Rebindable under *Options → Controls → Structure Overlay*.

## Project layout

```
src/main/java/dev/xaeroaddons/structureoverlay/
  StructureOverlayMod.java        mod entry point
  client/                         keybinds, tick handling, element registration
  config/OverlayConfig.java       persisted JSON choices (config/structureoverlay.json)
  data/                           region reader, structure scanning, caching
  element/                        Xaero map-element renderer (markers + labels)
  gui/StructureMenuScreen.java    the menu
  mixin/GuiMapMixin.java          button on Xaero's map screen
```
