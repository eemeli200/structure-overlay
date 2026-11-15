# Structure Overlay for Xaero's World Map

A NeoForge addon for **Xaero's World Map** that draws a toggleable overlay of
**structure starts** (villages, monuments, fortresses, ancient cities, ...) on the full
screen map, with a menu for choosing exactly which structure types are shown.

In singleplayer it reads the world save directly. On a server, install it on the server as
well and the server scans its save and streams the structure list to every client that has
the addon; a server without it keeps working exactly as before, the overlay just has
nothing to draw and the menu says so.

- **Menu** (default key `O`, also a *Structures* button on the Xaero map screen): master
  overlay toggle, label toggle, footprint *Line*/*Fill* toggles, rescan, and a paginated
  checklist of every structure type found in the world.
- **Search box** in the menu: filters the type list live, matching both raw ids
  (`village_plains`) and display names (`Simple Dungeon`).
- **Sort modes**: by name, by how many were found, or grouped by mod/namespace.
- **Per-type colours**: every entry shows its marker colour; shift-click a type cell to
  re-roll the colour, ctrl+shift-click to reset it to the default derived from the id.
  Overrides are persisted in the config (`structureColors`).
- **Fill opacity** (10/20/30/50 %) and **marker size** (0.5x - 2x) presets.
- **CSV export**: writes every discovered structure (dimension, id, name, coordinates,
  footprint) to `structureoverlay-structures.csv` next to the game directory.
- **Nearest structure** readout in the menu status line.
- **Quick toggle** (default key `P`): turn the whole overlay on/off without opening a menu.
- Markers are colour-coded per structure type, show the structure name (on hover, or
  always via the *Labels* toggle) and coordinates, and are hoverable. The hover label and
  tooltip add the distance from the player plus the footprint size.
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

Install this jar alongside NeoForge + Xaero's World Map. Everything renders client side;
installing the mod on the server is optional and only needed to make the overlay work in
multiplayer (see [Multiplayer](#multiplayer)).

## Where the structure data comes from

Positions are read **from the world save on disk** (`region/*.mca`), exactly like vanilla
stores them: every chunk carries a copy of each structure start inside
`structures -> starts`, with the start chunk recorded as `ChunkX`/`ChunkZ`
(`StructureStart.save`). So:

- ✅ Full-map coverage in singleplayer / LAN worlds, no matter how far you have explored.
- ✅ On a server that runs this addon: the server scans its own save and sends the result to
  you (see [Multiplayer](#multiplayer)).
- ⚠️ On a server without the addon the save does not exist on your machine and there is
  nobody to ask, so the overlay stays empty (the menu says so).

Scanning happens on a background thread when you join a world, and again via the
**Rescan** button. Results are de-duplicated per dimension and cached until something
changes.

Scans are **incremental**: each region file's result is cached with its modification time
and size in `<world>/structureoverlay-cache.json`, so a re-scan only re-reads the region
files that actually changed. After a Chunky pre-generation run that turns a minutes-long
full scan into a rescan of just the new files. Shift-clicking *Rescan* clears the cache
and forces a full re-read.

## Multiplayer

Install the mod on the server as well and the overlay works there too:

- The client asks the server for the structure list when it joins. The server scans its own
  save off-thread (on a dedicated server: the `world/` folder), caches the result for the
  life of the server process, and streams it back in batches.
- The menu shows the transfer's progress, and the result then behaves exactly like a local
  scan: same markers, filtering, colours, nearest readout and CSV export.
- *Rescan* in the menu asks the server to rescan (shift-click forces a full re-read). The
  incremental region-file cache is used on the server side, so only changed files are read.

Graceful degradation, in both directions:

| Server | Client | Result |
|---|---|---|
| no addon / vanilla | addon | Overlay empty, menu says the server has no data |
| addon | no addon | Nothing is ever sent - the server checks that the client can receive |
| addon | addon | Full overlay from the server's save |

The payloads are registered as NeoForge *optional* payloads and every send is guarded by
`hasChannel(...)`, so the addon never blocks a connection in either direction and a server
without it never receives a packet it cannot decode. Wire format (protocol version `1`):
the client sends `structures/request`, the server replies with `structures/header` (totals),
any number of `structures/batch` payloads (up to 256 starts each), then
`structures/complete` - or a single `structures/unavailable` when it has no readable save.

## How the overlay is drawn

The addon plugs into Xaero's own element-rendering pipeline
(`WorldMap.mapElementRenderHandler`), which is the same pipeline used for waypoints and
player markers - so zoom, panning, culling, dimension switching and hover detection all
behave exactly like native Xaero elements. The only mixin is a small injection into
`GuiMap.init()` that adds the *Structures* button to the map screen.

## Building

```bash
./gradlew build
```

Produces `build/libs/structureoverlay-1.2.0.jar`. The wrapper pins Gradle 8.10.2 and needs
only a JDK 21; CI runs the same command on every push and pull request.

### Tests

```bash
gradle test
```

- `RegionChunkReaderTest` / `StructureScannerTest` round-trip synthetic `.mca` files
  (zlib/gzip/uncompressed, corrupt chunks, deduplication, multi-dimension layouts).
- `IncrementalScanTest` proves the incremental cache: unchanged files are answered from
  the cache, changed/deleted ones are re-read, and the cache survives a restart.
- `StructureQueryTest` (search + sort modes), `StructureColorsTest` (colour hashing and
  overrides) and `StructureCsvTest` (export format) cover the menu logic.
- `StructureAccumulatorTest` / `StructureProtocolTest` cover the multiplayer path:
  assembling and de-duplicating incoming batches, and codec round trips of every payload.
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
  net/                            server <-> client sync (optional payloads, server scan)
  mixin/GuiMapMixin.java          button on Xaero's map screen
```
