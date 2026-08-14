# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A standalone Maven project: a trimmed extract of RuneLite's `net.runelite.cache` library (cache filesystem + definition loaders) plus a custom `net.runelite.cache.editor` package that is the actual product — a 2D/3D map editor for an OSRS private-server ("Reason"/RSPS) cache. GroupId is `com.reason`. Not affiliated with RuneLite upstream.

## Build & run

```
# Build (Windows — produces target/osrs-map-editor.jar)
mvnw.cmd -DskipTests package

# Run
java -jar target/osrs-map-editor.jar [--cache DIR] [--xteas FILE] [--region ID]
```

- JDK 11+ required (`maven.compiler.release=11`).
- Output is a **shaded fat jar** (maven-shade-plugin, main class `net.runelite.cache.editor.MapEditor`) — bundles guava/gson/flatlaf/slf4j/jna/commons, nothing else needed at runtime.
- No `--cache` argument → folder chooser dialog. Last-used cache/xteas paths persist in `~/.osrs-map-editor.properties`; hotbar layout in `~/.osrs-map-editor-hotbar.json`.
- No test suite; no CI.

**Batch scripts:** `build-editor.bat` and `run-editor.bat` wrap the above.

## README trust level

Two READMEs exist with different trust levels:
- **Top-level `README.md`** — accurate for this standalone single-module repo; use it for build/run/features.
- **`src/main/java/net/runelite/cache/editor/README.md`** — inherited from the original multi-module monorepo; its **build commands are stale** (references a `cache` submodule, `-pl cache`, paths that don't exist here), but its **feature, UI, and controls documentation is accurate and detailed** — use it for "how does X work in the editor."

## Architecture

### Cache filesystem (`net.runelite.cache.fs`)

Classic Jagex RS2 cache format read by `DiskStorage`:
```
Store → Index (one per IndexType: MAPS, CONFIGS=2, MODELS=7, …) → Archive → FSFile
```
`Container` handles per-entry decompression (`CompressionType`: none/gzip/bzip2). Each content type has a `definitions/*Definition` + `definitions/loaders/*Loader` pair; `net.runelite.cache.*Manager` classes wrap an `Index` + loader for convenient `load()`/`getXxx(id)` access.

### Editor package (`net.runelite.cache.editor`)

| File | Role |
|---|---|
| `MapEditor.java` | Entry point, arg parsing, persists last cache/keys |
| `MapEditorFrame.java` | Swing UI — canvas, tool column, ribbon, palette, dialogs |
| `MapEditorService.java` | **All cache I/O**: open store, list/load/save regions, add regions |
| `RegionModel.java` | Mutable in-memory region under edit |
| `MapRenderer.java` | 2D top-down renderer |
| `SceneBuilder.java` + `Renderer3D.java` | Build + software-rasterise the 3D scene (z-buffer, textures, orbit camera) |
| `MapSaver.java` / `LocationsSaver.java` | Hand-written **inverse encoders** of `MapLoader`/`LocationsLoader` |
| `ObjectDefManager.java` / `NpcDefManager.java` | Tolerant idx-2 loaders (see gotchas) |
| `ModelManager.java` | Model geometry from idx 7 |
| `JsonXteaKeyProvider.java` | Reads `xteas.json` / `region_keys.json` |
| `SpawnLoader.java` | Reads server-side spawn JSON (not part of the cache) |

## Critical gotchas

**Duplicate file IDs in config archive (idx 2).** This RSPS cache has duplicate file ids in the config index. Stock RuneLite `ObjectManager` throws on it. `ObjectDefManager`/`NpcDefManager` work around this by splitting the archive *positionally* instead of by id, recovering all ~57k object / ~15k NPC defs. Some defs use unrecognized opcodes and are silently skipped — this is expected, not a bug.

**Locations are unencrypted.** `MapEditorService` tries the XTEA key first, then falls back to no encryption automatically. The fallback is intentional.

**Error-tolerant manager loading.** Every manager `load()` call in `MapEditorService.open()` is individually try/caught so one broken definition archive doesn't prevent the map view from opening. Follow this pattern for any new manager you add.

**Saving grows the dat2 file.** Saving appends new sectors to `main_file_cache.dat2`; old sectors become dead space. The file only grows — compaction needs an external repack tool. Always back up the cache before bulk edits.

**Encoder correctness bar.** `MapSaver` and `LocationsSaver` are verified byte-identical to the originals across thousands of real regions. Hold any encoder/loader changes to the same round-trip bar: load → edit → save → reload must produce identical bytes.

**Spawn data is external.** NPC/object spawn overlays come from `data/npcs/spawns` / `data/objects/spawns` JSON outside this repo (the server's data directory). Don't look for spawn data inside `main_file_cache.*`.
