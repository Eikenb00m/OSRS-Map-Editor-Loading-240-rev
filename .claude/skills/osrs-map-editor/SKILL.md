---
name: osrs-map-editor
description: Use when working in this repo, or any similar standalone Java tool built on RuneLite's net.runelite.cache library — building/running the map editor jar, reading or modifying the Jagex cache filesystem (Store/Index/Archive), terrain or location save-back encoding, or object/NPC definitions from an RSPS repack cache.
---

# OSRS Map Editor (RuneLite cache-based)

## What this repo is
A standalone, single-module Maven project: a trimmed copy of RuneLite's
`net.runelite.cache` library (cache filesystem + definition loaders, MIT/BSD-style,
© Adam@sigterm.info) plus a custom `net.runelite.cache.editor` package that is the
actual product — a 2D/3D map editor for an OSRS private-server ("Reason"/RSPS) cache.
`groupId` is `com.reason`. Not affiliated with RuneLite upstream; this is a fork/extract,
so don't expect it to track RuneLite changes.

## Build & run
- JDK 11+ required (`maven.compiler.release=11`).
- Build: `./mvnw -DskipTests package` (Git Bash) or double-click `build-editor.bat`.
  Output is a **shaded fat jar** `target/osrs-map-editor.jar` (maven-shade-plugin,
  main class `net.runelite.cache.editor.MapEditor`) — bundles guava/gson/flatlaf/etc,
  nothing else needed at runtime.
- Run: `java -jar target/osrs-map-editor.jar [--cache DIR] [--xteas FILE] [--region ID]`
  or `run-editor.bat`. No `--cache` → folder chooser. Last-used cache/xteas paths are
  remembered in `~/.osrs-map-editor.properties`; hotbar layout in `~/.osrs-map-editor-hotbar.json`.
- No test suite in this repo (build always skips tests); no CI config.
- **Two READMEs, different trust levels:** top-level `README.md` matches this
  standalone single-module repo — trust it for build/run. The nested
  `src/main/java/net/runelite/cache/editor/README.md` was inherited from the original
  multi-module monorepo this was extracted from (references a `cache` submodule,
  `-pl cache`, `client reasonps/client/` paths that don't exist here) — its build
  commands are stale, but its **feature/UI/controls documentation is accurate and
  detailed**, use it for "how do I do X in the editor".

## Cache filesystem model (net.runelite.cache.fs)
Classic Jagex 317-era RS2 cache format, physically `main_file_cache.dat2` + `.idx*`
files read by `DiskStorage`:
```
Store -> Index (one per IndexType, e.g. MAPS, CONFIGS=2, MODELS=7) -> Archive -> FSFile
```
`Container` handles per-entry decompression (`CompressionType`: none/gzip/bzip2).
Each cache content type (items, npcs, objects, maps, locations, models, overlays,
underlays, textures, sprites, interfaces, sequences, DB tables, enums, structs,
varbits, worldmap, sounds) has a `definitions/*Definition` + `definitions/loaders/*Loader`
pair; top-level `net.runelite.cache.*Manager` classes wrap an `Index` + loader for
convenient `load()`/`getXxx(id)` access.

## The editor package — architecture
| File | Role |
|---|---|
| `MapEditor.java` | entry point, arg parsing, remembers last cache/keys |
| `MapEditorFrame.java` | Swing UI — canvas, tool column, ribbon, palette |
| `MapEditorService.java` | **all cache I/O**: open store, list/load/save regions, add regions |
| `RegionModel.java` | mutable in-memory region under edit |
| `MapRenderer.java` | 2D top-down renderer |
| `SceneBuilder.java` + `Renderer3D.java` | build + software-rasterise the 3D scene (z-buffer, textures, orbit camera) |
| `MapSaver.java` / `LocationsSaver.java` | hand-written **inverse encoders** of `MapLoader`/`LocationsLoader` |
| `ObjectDefManager.java` / `NpcDefManager.java` | tolerant idx-2 loaders (see gotchas) |
| `ModelManager.java` | model geometry from idx 7 |
| `JsonXteaKeyProvider.java` | reads `xteas.json`/`region_keys.json` |
| `SpawnLoader.java` | reads server-side spawn JSON (not part of the cache) |

## Repo-specific gotchas (learn these before touching cache I/O)
- **This cache is an RSPS repack with duplicate file ids** in the config archive
  (idx 2). Stock RuneLite `ObjectManager` throws on it. `ObjectDefManager`/`NpcDefManager`
  work around this by splitting the archive **positionally** instead of by id,
  recovering all ~57k object / ~15k NPC defs. A few defs use opcodes this RuneLite
  revision doesn't recognize and are silently skipped — expect that, don't "fix" it
  as a bug without checking first.
- **Locations are unencrypted in this cache.** `MapEditorService` tries the XTEA key
  from the key file first, then falls back to no encryption automatically — that
  fallback is intentional, not a missing-key bug.
- **Every manager load in `MapEditorService.open()` is individually try/caught** so
  one broken definition archive (common on custom caches) doesn't stop the map view
  from opening at all — follow that pattern for any new manager you wire in.
- **Saving appends to `main_file_cache.dat2`**; old sectors become dead space and the
  file only grows. Always back up the cache before bulk edits; compaction needs an
  external repack tool.
- The README claims both encoders are verified byte-identical to the originals across
  thousands of real regions, and the save path is tested via load→edit→save→reload —
  hold new encoder/loader changes to that same round-trip bar.
- Server NPC/object spawn overlays come from `data/npcs/spawns` / `data/objects/spawns`
  JSON **outside this repo/cache** (the server's data dir) — don't go looking for spawn
  data inside `main_file_cache.*`.
