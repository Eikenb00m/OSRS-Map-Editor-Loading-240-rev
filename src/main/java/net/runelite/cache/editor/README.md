# OSRS Map Editor

A standalone 2D map editor for the Reason server cache, built directly on the
`net.runelite.cache` library already bundled in this client. It reads and writes
your real OSRS cache format (index 5), so there is no separate export/import step
and no dependency on RSPSi.

## What it does

- **2D view** – top-down render of any 64×64 map square, per plane (0–3),
  using the cache's real underlay/overlay colours.
- **3D view** – a real perspective 3D render with terrain elevation, the actual
  3D **object models** from the cache (buildings, walls, trees, furniture) with
  **per-face textures** (leafy trees, stone walls, tiled roofs, water), and
  **NPC models** drawn at their server spawn points. Fly around with WASD; drag
  to rotate, wheel to zoom.
- **Edit tiles** – paint underlays (ground), overlays (paths/water) with
  path+rotation, set tile heights, and edit collision/bridge flags.
- **Edit objects** – place, inspect and delete scenery/walls/ground decoration
  by object id (searchable by name), type and orientation.
- **Add new regions** – create brand-new empty map squares (custom zones) that
  don't exist in the cache yet.
- **Server spawn overlay** – overlays the server's NPC spawns (red) and object
  spawns (cyan) read from `data/npcs/spawns` and `data/objects/spawns`, in both
  2D and 3D. These live in the server, not the cache.
- **Save back to cache** – edits are re-encoded, compressed and written straight
  into `main_file_cache.*`.

## 3D rendering internals

The 3D view is a dependency-free software rasteriser:

- `ObjectDefManager` – tolerant object-definition loader. This cache's config
  archive has a few duplicate file ids (from an RSPS repack) that make RuneLite's
  stock `ObjectManager` throw; this loader splits the archive positionally and
  recovers all ~57k definitions (names, sizes, **model ids**).
- `ModelManager` – loads model geometry (vertices/faces/HSL colours) from index 7.
- `SceneBuilder` – builds a terrain mesh from tile heights + colours and places
  each object's model (oriented, scaled, seated on the terrain), plus spawn poles.
- `Renderer3D` – z-buffered triangle rasteriser with an orbiting perspective
  camera. Backface culling; per-face flat lighting.

## Running

From `client reasonps/client/`, double-click or run:

```
run-map-editor.bat
```

It launches the already-compiled editor (uses `JAVA_HOME`, else `java` on PATH).

If you change the editor source, rebuild from Git Bash in that folder:

```
./mvnw -pl cache -DskipTests -Dcheckstyle.skip=true compile
./mvnw -pl cache dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeScope=runtime
```

(The Maven wrapper only ships the Unix `mvnw`, so rebuilds go through Git Bash,
not cmd.)

To point at a different cache or key file:

```
java -cp "cache/target/classes;cache/target/dependency/*" ^
  net.runelite.cache.editor.MapEditor ^
  --cache  "path/to/cache" ^
  --xteas  "path/to/region_keys.json" ^
  --region 12850
```

If `--cache` is omitted a folder chooser appears. If no XTEA keys file
(`region_keys.json` / `xteas.json`) is found next to the cache, a file chooser
appears for it too — Cancel to continue without keys (fine for this cache, whose
locations are unencrypted).

The UI uses a flat dark theme (FlatLaf), a vertical **tool button column on the
left** (Select / Underlay / Overlay / Height / Flags / Place / Delete, with icons),
and solid-filled colour swatches in the palette tabs.

## Controls

- **Toolbar** – Open Cache, **Go To…** (opens a clickable **world map** of all
  regions — thumbnails you click to jump to; "Type id / coords…" button for the
  old id/`x,y`/`wX,Y` entry), **Add Region…** (offers the **next free region id**
  automatically, or pick an empty cell on the world map), Save (Ctrl+S), Reload,
  3D View / Split toggles, plane selector, zoom, layer toggles.
- **Split 3D/2D** – toolbar toggle showing both views at once with a **draggable
  divider**. Default is side-by-side like the official Jagex editor (**2D left,
  3D right**); the ⇄/⇅ button swaps to stacked. Edits in either view update both.
- **Overlay tile shapes** – overlays render with their real OSRS shapes as
  **exact geometric cuts** (clean diagonals/halves/corners, not pixel steps) in
  both 2D and 3D. A **shape picker** in the Tools tab paints them: pick a shape
  glyph, rotate ↻, click tiles — this is how you cut road/water corners.
- **Place NPCs** – the **NPC tool** (left column): pick an NPC in the NPCs tab →
  "Use for Place NPC" (or double-click) → click tiles to place spawns. They render
  immediately (Spawns layer auto-enables); **Save** merges them into
  `data/npcs/spawns/editor_spawns.json` in the server's format. The Delete tool
  removes editor-placed spawns.
- The top ribbon buttons (Tools / Underlay / Overlay / Objects / NPCs / Models /
  Textures) open that panel on the right; the tool column is on the left.
- **Height sculpting** – with the Height tool, **click = raise** the terrain by
  the *Height step* (ribbon), **right-click / shift = lower**, **ctrl+click = set
  the absolute value** from the field (blank = auto). Works with the brush radius
  and area fill; a **Height tint** layer checkbox shows a blue→red relief view.
- **Undo / redo** – Ctrl+Z / Ctrl+Y (or the ribbon buttons); covers tile paints,
  height sculpts, area fills and object place/move/rotate/delete (50 steps).
- **Ribbon** – the tools now live in a toolbar row at the top (Select / Underlay /
  Overlay / Height / Flags / Place / Delete + brush, height step, area fill,
  undo/redo).
- **Minimap** – top-right panel showing the whole region with the current 2D
  viewport rectangle; click or drag on it to navigate.
- **Same-id flash** – selecting an object highlights it yellow and flashes every
  other placement of the same object id (orange) in both 2D and 3D; the selected
  object's model also previews live in the side panel.
- **Element hotbar** – Jagex-style quick-slot bar at the bottom: 20 slots bound
  to **1–5 / Q–T / A–G / Z–B**. Right-click a slot to assign your current tool +
  parameters (an overlay shape, an object, a height…), then press the key or
  click the slot to switch to it instantly. Saved to
  `~/.osrs-map-editor-hotbar.json` across restarts. (Keys are ignored while
  typing in a field or flying the 3D camera.)
- **Hide/Show elements** – layer checkboxes in the Tools tab (walls & doors,
  scenery & roofing, ground decor, overlays) affecting both 2D and 3D.
- **2D map** – **mouse wheel zooms** (keeping the tile under the cursor stable);
  scrollbars pan.
- **3D view** – fills the window and resizes with it. Controls:
  - **Middle-mouse drag** – rotate/tilt the camera. **Wheel** – zoom. **WASD** –
    fly (Q/E up/down). Click the view first so it has keyboard focus.
  - **Left-click** – with the *Select* tool, selects the object under the cursor
    (highlighted yellow); with a paint/edit tool, applies it to the ground tile.
  - **Right-click** – context menu on the object under the cursor: **Rotate 90°**,
    **Move** (then left-click a destination tile), **Delete**.
  - Selection also shows Rotate/Move/Delete buttons + info in the side panel.
  - It renders at reduced resolution while you move and sharpens when you stop.
    (Object picks use a colour-id pass over the models; tile picks use one over
    the terrain.)
- **Palette (right, tabbed)**:
  - **Tools** – Select / Paint Underlay / Paint Overlay / Set Height / Edit Flags /
    Place / Delete Object, a **Brush radius** spinner (0 = 1×1, 1 = 3×3, …), an
    **Area fill** checkbox (drag a rectangle in 2D to fill it with the active
    terrain tool), the parameter fields, and the selection/tile inspector.
  - **Underlay / Overlay** – clickable colour swatches; click one to select that
    id and the matching paint tool.
  - **Objects** – searchable list of all ~57k objects with a 3D preview of the
    selected one; click "Use for Place Object" (or double-click) to place it.
  - **NPCs** – searchable list of all ~15k NPCs with a 3D preview of the selected
    NPC's model (view-only; NPCs are server spawns, not cache objects).
  - **Models** – enter/step a raw model id and preview its 3D geometry.
  - **Textures** – thumbnail grid of the baked textures.

The 2D map auto-fits to fill and centre in its area (and re-fits on resize); the
mouse wheel zooms it and **middle-mouse drag pans** it. Selecting a tile in the 2D
map also **highlights that tile in the 3D view** (great in split mode).

## Notes about this cache

- **Locations are stored unencrypted.** The editor detects this automatically:
  it tries the XTEA key from the key file, and falls back to no encryption, which
  is what this cache uses. Objects are therefore editable in every region. New
  regions are written unencrypted to match.
- **Object definitions are recovered despite a repack quirk.** This cache's config
  index (idx 2) has a few duplicate file ids that make RuneLite's stock parser
  throw; `ObjectDefManager` works around it and recovers all ~57k object
  definitions, so names, sizes and 3D models are all available. A handful of
  defs use opcodes this RuneLite revision doesn't recognise and are skipped.
- Saving **appends** to `main_file_cache.dat2` (old sectors become dead space).
  Repacking the cache elsewhere will compact it. Always keep a backup of your
  cache before bulk edits.

## Code layout

| File | Purpose |
|------|---------|
| `MapEditor.java` | entry point / arg parsing |
| `MapEditorFrame.java` | Swing UI (canvas, tools, inspector, dialogs) |
| `MapRenderer.java` | 2D top-down region renderer |
| `MapEditorService.java` | all cache I/O: list / load / save / add regions |
| `RegionModel.java` | mutable in-memory region being edited |
| `MapSaver.java` | terrain encoder (inverse of `MapLoader`) |
| `LocationsSaver.java` | locations encoder (inverse of `LocationsLoader`) |
| `JsonXteaKeyProvider.java` | reads `xteas.json` / `region_keys.json` keys |
| `ObjectDefManager.java` | tolerant object-definition loader (idx 2) |
| `NpcDefManager.java` | tolerant NPC-definition loader (idx 2) |
| `ModelManager.java` | model geometry loader (idx 7) |
| `SceneBuilder.java` | builds the 3D scene (terrain + models + textures + spawns) |
| `Renderer3D.java` | software z-buffered 3D rasteriser + texture mapping + camera |
| `SpawnLoader.java` | reads server NPC/object spawn json |

Textures are baked from sprites (`MapEditorService.getTexturePixels`) and sampled
per-pixel; NPC/object spawns are drawn as their real models with a coloured-pole
fallback when a model isn't available.

Both encoders are verified byte-for-byte identical to the originals across
thousands of the server's real regions, and the save path is validated by
loading, editing, saving and reloading a copy of the live cache.
