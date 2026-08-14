# OSRS Map Editor (standalone)

A self-contained copy of the map editor that builds into a single runnable jar.

## Build
From this folder (Git Bash):
    ./mvnw -DskipTests package
…or on Windows double-click **build-editor.bat**. Output: `target/osrs-map-editor.jar`.

Requires a JDK 11+ to build. The jar bundles every dependency (FlatLaf, the
RuneLite cache library, gson, guava, …) so nothing else is needed at runtime.

## Run
    java -jar target/osrs-map-editor.jar
…or double-click **run-editor.bat**. With no arguments it shows a "pick a cache
folder" dialog. To open a cache directly:
    java -jar target/osrs-map-editor.jar --cache "C:\path\to\cache" --region 12850

Runs on any PC (Windows/Mac/Linux) with a Java 11+ runtime installed — no other setup.

## Notes
- Source under `src/main/java/net/runelite/cache` is a copy of the client's cache
  module plus the `editor` package (the RS2-script/world-map bits it doesn't need
  were dropped so it builds without ANTLR).
- To ship to someone without Java, run `jpackage` on the jar to produce a native
  installer with a bundled runtime.
