/*
 * All cache I/O for the map editor lives here: opening the store, listing which
 * map squares exist, loading one for editing, saving edits back, and creating
 * brand new map squares. Rendering/definition lookups (underlay/overlay/object)
 * are exposed too.
 */
package net.runelite.cache.editor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.IndexType;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.TextureDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.definitions.loaders.LocationsLoader;
import net.runelite.cache.definitions.loaders.MapLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Container;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.fs.jagex.CompressionType;
import net.runelite.cache.index.FileData;
import net.runelite.cache.util.Djb2;

public class MapEditorService
{
	private final File cacheDir;
	private final JsonXteaKeyProvider keyProvider;

	private Store store;
	private Storage storage;
	private Index mapIndex;
	private MapCodec.Fmt mapFmt; // terrain encoding for this cache (detected on first load)

	private UnderlayManager underlayManager;
	private OverlayManager overlayManager;

	// Overlays created on the fly so any texture can be painted (a tile references a
	// texture only via an overlay). Kept separate from the loaded set and written into
	// the cache's OVERLAY config archive on save.
	private Index configIndex;
	private Archive overlayArchive;
	private final Map<Integer, OverlayDefinition> customOverlays = new java.util.LinkedHashMap<>();
	// texture id -> the dedicated texture-only overlay the editor created for painting it, so the
	// Textures tab never reuses meaningful existing overlays (e.g. overlay 5) and keeps them separate.
	private final Map<Integer, Integer> dedicatedTextureOverlay = new java.util.HashMap<>();
	private boolean overlaysDirty;

	private ObjectDefManager objectDefs;
	private NpcDefManager npcDefs;
	private ModelManager models;
	private AnimationManager animations;

	// textures (baked lazily from sprites)
	public static final int TEX_SIZE = 128;
	private TextureManager textureManager;
	private SpriteManager spriteManager;
	private final Map<Integer, int[]> texturePixels = new HashMap<>();
	private final Map<Integer, Integer> textureAverage = new HashMap<>();

	// nameHash("m{x}_{y}") -> regionId, built once so listing is O(archives).
	private final Map<Integer, Integer> mapNameHashToRegion = new HashMap<>();

	// server-side spawns, indexed by region id
	private final Map<Integer, List<SpawnLoader.Spawn>> spawnsByRegion = new HashMap<>();
	private int spawnCount;

	public MapEditorService(File cacheDir, JsonXteaKeyProvider keyProvider)
	{
		this.cacheDir = cacheDir;
		this.keyProvider = keyProvider;
	}

	public File getCacheDir()
	{
		return cacheDir;
	}

	public void open() throws IOException
	{
		store = new Store(cacheDir);
		store.load();
		storage = store.getStorage();
		mapIndex = store.getIndex(IndexType.MAPS);

		// These are only used for colours and object names/sizes. A custom cache
		// can have a config archive this RuneLite revision can't parse, so never
		// let a manager failure stop the map itself from opening.
		try
		{
			underlayManager = new UnderlayManager(store);
			underlayManager.load();
		}
		catch (Exception ex)
		{
			underlayManager = null;
			System.err.println("Underlay defs unavailable: " + ex.getMessage());
		}
		try
		{
			overlayManager = new OverlayManager(store);
			overlayManager.load();
			// Handle to the OVERLAY config archive so new texture-overlays can be saved.
			configIndex = store.getIndex(IndexType.CONFIGS);
			overlayArchive = configIndex.getArchive(net.runelite.cache.ConfigType.OVERLAY.getId());
		}
		catch (Exception ex)
		{
			overlayManager = null;
			System.err.println("Overlay defs unavailable: " + ex.getMessage());
		}
		// Tolerant object-definition loader (recovers defs despite duplicate file
		// ids in this cache's config archive) + model geometry from index 7.
		try
		{
			objectDefs = new ObjectDefManager();
			objectDefs.load(store);
			System.out.println("Loaded " + objectDefs.size() + " object definitions.");
			applyObjectPatches();
		}
		catch (Exception ex)
		{
			objectDefs = null;
			System.err.println("Object defs unavailable (names/3D disabled): " + ex.getMessage());
		}
		try
		{
			models = new ModelManager(store);
		}
		catch (Exception ex)
		{
			models = null;
			System.err.println("Models unavailable (3D disabled): " + ex.getMessage());
		}
		try
		{
			animations = new AnimationManager(store);
			animations.load();
			System.out.println("Loaded " + animations.sequenceCount() + " animation sequences.");
		}
		catch (Exception ex)
		{
			animations = null;
			System.err.println("Animations unavailable: " + ex.getMessage());
		}
		try
		{
			npcDefs = new NpcDefManager();
			npcDefs.load(store);
			System.out.println("Loaded " + npcDefs.size() + " npc definitions.");
		}
		catch (Exception ex)
		{
			npcDefs = null;
			System.err.println("Npc defs unavailable: " + ex.getMessage());
		}
		try
		{
			spriteManager = new SpriteManager(store);
			spriteManager.load();
			textureManager = new TextureManager(store);
			textureManager.load();
			System.out.println("Loaded " + textureManager.getTextures().size() + " textures.");
		}
		catch (Exception ex)
		{
			spriteManager = null;
			textureManager = null;
			System.err.println("Textures unavailable: " + ex.getMessage());
		}

		for (int x = 0; x < 256; x++)
		{
			for (int y = 0; y < 256; y++)
			{
				mapNameHashToRegion.put(Djb2.hash("m" + x + "_" + y), (x << 8) | y);
			}
		}

		// Server spawns live in the data/ folder (parent of the cache folder).
		try
		{
			SpawnLoader spawnLoader = new SpawnLoader();
			spawnLoader.load(cacheDir.getParentFile());
			for (SpawnLoader.Spawn s : spawnLoader.getSpawns())
			{
				spawnsByRegion.computeIfAbsent(s.regionId(), k -> new ArrayList<>()).add(s);
			}
			spawnCount = spawnLoader.size();
			System.out.println("Loaded " + spawnCount + " server spawns.");
		}
		catch (Exception ex)
		{
			System.err.println("Spawns unavailable: " + ex.getMessage());
		}
	}

	public List<SpawnLoader.Spawn> getSpawnsInRegion(int regionId)
	{
		return spawnsByRegion.getOrDefault(regionId, java.util.Collections.emptyList());
	}

	// ---- NPC spawns placed in the editor (saved to server spawn json) ----

	private final List<SpawnLoader.Spawn> sessionSpawns = new ArrayList<>();

	/** orientation: 0=S, 1=W, 2=N, 3=E (matches the spawn json direction letters). */
	public SpawnLoader.Spawn addNpcSpawn(int id, String name, int x, int y, int z, int orientation)
	{
		SpawnLoader.Spawn s = new SpawnLoader.Spawn(true, id, x, y, z, 10, orientation & 3, name);
		sessionSpawns.add(s);
		spawnsByRegion.computeIfAbsent(s.regionId(), k -> new ArrayList<>()).add(s);
		spawnCount++;
		return s;
	}

	/** orientation: 0..3; type is the location/placement type (10 = normal scenery, 22 = ground decoration…). */
	public SpawnLoader.Spawn addObjSpawn(int id, int x, int y, int z, int type, int orientation)
	{
		SpawnLoader.Spawn s = new SpawnLoader.Spawn(false, id, x, y, z, type, orientation & 3, null);
		sessionSpawns.add(s);
		spawnsByRegion.computeIfAbsent(s.regionId(), k -> new ArrayList<>()).add(s);
		spawnCount++;
		return s;
	}

	/** The (topmost) NPC spawn on a world tile, or null. */
	public SpawnLoader.Spawn npcSpawnAt(int x, int y, int z)
	{
		int regionId = ((x >> 6) << 8) | (y >> 6);
		java.util.List<SpawnLoader.Spawn> lst = spawnsByRegion.get(regionId);
		if (lst != null)
		{
			for (int i = lst.size() - 1; i >= 0; i--)
			{
				SpawnLoader.Spawn s = lst.get(i);
				if (s.npc && s.x == x && s.y == y && s.z == z)
				{
					return s;
				}
			}
		}
		return null;
	}

	/** The (topmost) server spawn on a world tile — NPC or object — or null. */
	public SpawnLoader.Spawn spawnAt(int x, int y, int z)
	{
		int regionId = ((x >> 6) << 8) | (y >> 6);
		java.util.List<SpawnLoader.Spawn> lst = spawnsByRegion.get(regionId);
		if (lst != null)
		{
			for (int i = lst.size() - 1; i >= 0; i--)
			{
				SpawnLoader.Spawn s = lst.get(i);
				if (s.x == x && s.y == y && s.z == z)
				{
					return s;
				}
			}
		}
		return null;
	}

	public void removeSpawn(SpawnLoader.Spawn s)
	{
		java.util.List<SpawnLoader.Spawn> lst = spawnsByRegion.get(s.regionId());
		if (lst != null && lst.remove(s))
		{
			spawnCount--;
		}
		sessionSpawns.remove(s);
	}

	/** Replace a spawn with a rotated / moved copy (kept as an editor spawn), preserving its kind. */
	public SpawnLoader.Spawn replaceSpawn(SpawnLoader.Spawn s, int x, int y, int z, int orientation)
	{
		removeSpawn(s);
		return s.npc
			? addNpcSpawn(s.id, s.name, x, y, z, orientation)
			: addObjSpawn(s.id, x, y, z, s.type, orientation);
	}

	private static String directionLetter(int orientation)
	{
		switch (orientation & 3)
		{
			case 1: return "W";
			case 2: return "N";
			case 3: return "E";
			default: return "S";
		}
	}

	/** Remove an editor-placed NPC spawn on the exact tile; true if one was removed. */
	public boolean removeSessionNpcSpawnAt(int x, int y, int z)
	{
		for (java.util.Iterator<SpawnLoader.Spawn> it = sessionSpawns.iterator(); it.hasNext(); )
		{
			SpawnLoader.Spawn s = it.next();
			if (s.x == x && s.y == y && s.z == z)
			{
				it.remove();
				List<SpawnLoader.Spawn> lst = spawnsByRegion.get(s.regionId());
				if (lst != null)
				{
					lst.remove(s);
				}
				spawnCount--;
				return true;
			}
		}
		return false;
	}

	/**
	 * Append editor-placed spawns to the server's spawn files, in the server's formats:
	 *   NPCs    -> data/npcs/spawns/editor_spawns.json     {id,x,y,z,direction(letter),walkRange,world,npcName}
	 *   Objects -> data/objects/spawns/editor_spawns.json  {id,x,y,z,type,direction(int)}
	 * Returns a short description of what was written, or null if nothing to save.
	 */
	public String saveSessionSpawns() throws IOException
	{
		if (sessionSpawns.isEmpty())
		{
			return null;
		}
		java.util.List<SpawnLoader.Spawn> npcs = new ArrayList<>();
		java.util.List<SpawnLoader.Spawn> objs = new ArrayList<>();
		for (SpawnLoader.Spawn s : sessionSpawns)
		{
			(s.npc ? npcs : objs).add(s);
		}

		StringBuilder msg = new StringBuilder();
		if (!npcs.isEmpty())
		{
			File f = appendSpawns(new File(cacheDir.getParentFile(), "npcs/spawns"), npcs, true);
			msg.append(npcs.size()).append(" NPC → ").append(f.getName());
		}
		if (!objs.isEmpty())
		{
			File f = appendSpawns(new File(cacheDir.getParentFile(), "objects/spawns"), objs, false);
			if (msg.length() > 0)
			{
				msg.append(", ");
			}
			msg.append(objs.size()).append(" object → ").append(f.getName());
		}
		sessionSpawns.clear(); // already merged into the file(s); keep rendering via spawnsByRegion
		return msg.length() > 0 ? msg.toString() : null;
	}

	/** Merge the given spawns into {dir}/editor_spawns.json, preserving any existing entries. */
	private File appendSpawns(File dir, java.util.List<SpawnLoader.Spawn> spawns, boolean npc) throws IOException
	{
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("Can't create " + dir);
		}
		File file = new File(dir, "editor_spawns.json");
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		if (file.exists())
		{
			try (java.io.Reader r = new java.io.FileReader(file))
			{
				com.google.gson.JsonElement root = new com.google.gson.JsonParser().parse(r);
				if (root.isJsonArray())
				{
					arr = root.getAsJsonArray();
				}
			}
			catch (RuntimeException ignored)
			{
			}
		}
		for (SpawnLoader.Spawn s : spawns)
		{
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("id", s.id);
			o.addProperty("x", s.x);
			o.addProperty("y", s.y);
			o.addProperty("z", s.z);
			if (npc)
			{
				o.addProperty("direction", directionLetter(s.orientation));
				o.addProperty("walkRange", 0);
				o.addProperty("world", "ECO");
				o.addProperty("npcName", s.name == null ? "" : s.name);
			}
			else
			{
				o.addProperty("type", s.type);
				o.addProperty("direction", s.orientation & 3);
			}
			arr.add(o);
		}
		try (java.io.Writer w = new java.io.FileWriter(file))
		{
			new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr, w);
		}
		return file;
	}

	public int getSpawnCount()
	{
		return spawnCount;
	}

	public void close() throws IOException
	{
		if (store != null)
		{
			store.close();
		}
	}

	public JsonXteaKeyProvider getKeyProvider()
	{
		return keyProvider;
	}

	// ---- definition lookups (for rendering / palettes) -----------------

	public UnderlayDefinition getUnderlay(int id)
	{
		// id here is a real definition id (0 is a valid underlay). Callers pass tile-id minus 1.
		return (id < 0 || underlayManager == null) ? null : underlayManager.provide(id);
	}

	public OverlayDefinition getOverlay(int id)
	{
		if (id == 0)
		{
			return null;
		}
		OverlayDefinition custom = customOverlays.get(id);
		if (custom != null)
		{
			return custom;
		}
		return overlayManager == null ? null : overlayManager.provide(id);
	}

	/** Existing overlay id that renders {@code texId} (lowest), or -1 if none. */
	public int overlayForTexture(int texId)
	{
		int best = -1;
		for (OverlayDefinition o : getOverlays())
		{
			if (o.getTexture() == texId && (best < 0 || o.getId() < best))
			{
				best = o.getId();
			}
		}
		return best;
	}

	/**
	 * Returns a **dedicated** overlay id that paints the given texture. The editor keeps one
	 * texture-only overlay per texture (created on first use, reused after), so painting a texture
	 * never borrows a meaningful existing overlay like overlay 5 — textures and real overlays stay
	 * separate. New overlays are persisted to the cache by {@link #saveOverlays()}.
	 */
	public int getOrCreateOverlayForTexture(int texId)
	{
		Integer existing = dedicatedTextureOverlay.get(texId);
		if (existing != null && (customOverlays.containsKey(existing) || getOverlay(existing) != null))
		{
			return existing;
		}
		int id = nextFreeOverlayId();
		OverlayDefinition d = new OverlayDefinition();
		d.setId(id);
		d.setTexture(texId);
		d.setRgbColor(hasTextures() ? (getTextureAverage(texId) & 0xFFFFFF) : 0xFFFFFF);
		d.setSecondaryRgbColor(-1);
		d.setHideUnderlay(true); // the texture covers the tile
		d.calculateHsl();
		customOverlays.put(id, d);
		dedicatedTextureOverlay.put(texId, id);
		overlaysDirty = true;
		return id;
	}

	private int nextFreeOverlayId()
	{
		int max = 0;
		for (OverlayDefinition o : getOverlays())
		{
			max = Math.max(max, o.getId());
		}
		return max + 1;
	}

	public boolean hasUnsavedOverlays()
	{
		return overlaysDirty;
	}

	/**
	 * Writes any newly-created texture overlays into the cache's OVERLAY config
	 * archive (existing overlays are left byte-for-byte untouched — their file
	 * contents are reused as loaded, only the new files are appended).
	 */
	public void saveOverlays() throws IOException
	{
		if (!overlaysDirty || overlayArchive == null)
		{
			return;
		}
		byte[] cur = storage.loadArchive(overlayArchive);
		net.runelite.cache.fs.ArchiveFiles files = overlayArchive.getFiles(cur);
		for (OverlayDefinition d : customOverlays.values())
		{
			if (files.findFile(d.getId()) != null)
			{
				continue;
			}
			net.runelite.cache.fs.FSFile f = new net.runelite.cache.fs.FSFile(d.getId());
			f.setNameHash(0);
			f.setContents(encodeOverlay(d));
			files.addFile(f);
		}
		// Rebuild the archive's file-id table to match the (existing + new) files.
		java.util.Collection<net.runelite.cache.fs.FSFile> all = files.getFiles();
		FileData[] fd = new FileData[all.size()];
		int i = 0;
		for (net.runelite.cache.fs.FSFile f : all)
		{
			FileData e = new FileData();
			e.setId(f.getFileId());
			e.setNameHash(f.getNameHash());
			fd[i++] = e;
		}
		overlayArchive.setFileData(fd);
		writeArchive(overlayArchive, files.saveContents(), null, CompressionType.GZ);
		store.save(configIndex); // only the CONFIGS index table, not the whole cache
		overlaysDirty = false;
	}

	private byte[] encodeOverlay(OverlayDefinition d)
	{
		net.runelite.cache.io.OutputStream out = new net.runelite.cache.io.OutputStream();
		if (d.getRgbColor() != 0)
		{
			out.writeByte(1);
			out.write24BitInt(d.getRgbColor());
		}
		if (d.getTexture() != -1)
		{
			out.writeByte(2);
			out.writeByte(d.getTexture());
		}
		if (!d.isHideUnderlay())
		{
			out.writeByte(5);
		}
		if (d.getSecondaryRgbColor() != -1)
		{
			out.writeByte(7);
			out.write24BitInt(d.getSecondaryRgbColor());
		}
		out.writeByte(0);
		return out.flip();
	}

	public ObjectDefinition getObject(int id)
	{
		return objectDefs == null ? null : objectDefs.get(id);
	}

	// ---- object patches (1_patches/object/*.toml overriding jagex defs) -------
	// The binary cache only reflects patches as of the last repack, and RuneLite can't parse this
	// server's custom object opcodes anyway. So we read the TOML source patches directly and inject
	// them into the loaded object defs — the editor then shows the custom object (Trading Post, etc.)
	// matching the server, not the stale jagex version (Noticeboard). Injected into objectDefs so
	// every consumer (2D, 3D, browsers) sees the patched version.
	private void applyObjectPatches()
	{
		if (objectDefs == null)
		{
			return;
		}
		int applied = 0;
		File dir = new File(cacheDir, "toml/1_patches/object");
		File[] files = dir.isDirectory() ? dir.listFiles((d, n) -> n.endsWith(".toml")) : null;
		if (files == null)
		{
			return;
		}
		for (File f : files)
		{
			try
			{
				String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
				Integer id = tomlInt(text, "id");
				if (id == null)
				{
					id = Integer.parseInt(f.getName().replace(".toml", ""));
				}
				ObjectDefinition d = new ObjectDefinition();
				d.setId(id);
				String name = tomlString(text, "name");
				if (name != null)
				{
					d.setName(name);
				}
				int[][] models = tomlModels(text);
				if (models != null && models.length > 0)
				{
					int[] mdl = new int[models.length];
					int[] typ = new int[models.length];
					boolean anyType = false;
					for (int i = 0; i < models.length; i++)
					{
						mdl[i] = models[i][0];
						typ[i] = models[i][1];
						if (models[i][1] >= 0)
						{
							anyType = true;
						}
					}
					d.setObjectModels(mdl);
					d.setObjectTypes(anyType ? typ : null);
				}
				int[] size = tomlIntArray(text, "size");
				if (size != null && size.length >= 2)
				{
					d.setSizeX(size[0]);
					d.setSizeY(size[1]);
				}
				int[] ms = tomlIntArray(text, "model_size");
				if (ms != null && ms.length >= 3)
				{
					d.setModelSizeX(ms[0]);
					d.setModelSizeHeight(ms[1]);
					d.setModelSizeY(ms[2]);
				}
				int[] mo = tomlIntArray(text, "model_offset");
				if (mo != null && mo.length >= 3)
				{
					d.setOffsetX(mo[0]);
					d.setOffsetHeight(mo[1]);
					d.setOffsetY(mo[2]);
				}
				Integer anim = tomlInt(text, "animation");
				if (anim != null) { d.setAnimationID(anim); }
				Integer amb = tomlInt(text, "ambient");
				if (amb != null) { d.setAmbient(amb); }
				Integer con = tomlInt(text, "contrast");
				if (con != null) { d.setContrast(con); }
				Integer msc = tomlInt(text, "map_scene");
				if (msc != null) { d.setMapSceneID(msc); }
				Integer mic = tomlInt(text, "map_icon");
				if (mic != null) { d.setMapAreaId(mic); }
				Integer it = tomlInt(text, "interact_type");
				if (it != null) { d.setInteractType(it); }
				if ("true".equals(tomlString(text, "merge_normals"))) { d.setMergeNormals(true); }
				objectDefs.put(id, d);
				applied++;
			}
			catch (Exception ex)
			{
				// skip a malformed patch file
			}
		}
		if (applied > 0)
		{
			System.out.println("Applied " + applied + " object patches from 1_patches/object.");
		}
	}

	/** Raw value text after {@code key =} (handles bracket-balanced arrays + quoted strings), or null. */
	private static String tomlRaw(String text, String key)
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^" + key + "\\s*=\\s*").matcher(text);
		if (!m.find())
		{
			return null;
		}
		int i = m.end();
		if (i >= text.length())
		{
			return "";
		}
		char c = text.charAt(i);
		if (c == '[')
		{
			int depth = 0, j = i;
			for (; j < text.length(); j++)
			{
				char ch = text.charAt(j);
				if (ch == '[') { depth++; }
				else if (ch == ']') { depth--; if (depth == 0) { j++; break; } }
			}
			return text.substring(i, j);
		}
		if (c == '"')
		{
			int j = text.indexOf('"', i + 1);
			return text.substring(i, j < 0 ? text.length() : j + 1);
		}
		int j = i;
		while (j < text.length() && text.charAt(j) != '\n' && text.charAt(j) != '\r')
		{
			j++;
		}
		return text.substring(i, j).trim();
	}

	private static String tomlString(String text, String key)
	{
		String raw = tomlRaw(text, key);
		if (raw == null)
		{
			return null;
		}
		raw = raw.trim();
		if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\""))
		{
			return raw.substring(1, raw.length() - 1);
		}
		return raw;
	}

	private static Integer tomlInt(String text, String key)
	{
		String raw = tomlRaw(text, key);
		if (raw == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	private static int[] tomlIntArray(String text, String key)
	{
		String raw = tomlRaw(text, key);
		if (raw == null)
		{
			return null;
		}
		java.util.List<Integer> out = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(raw);
		while (m.find())
		{
			out.add(Integer.parseInt(m.group()));
		}
		int[] r = new int[out.size()];
		for (int i = 0; i < r.length; i++)
		{
			r[i] = out.get(i);
		}
		return r;
	}

	/** Parses {@code models = [[model,type], …]} into pairs; type -1 means "no location type". */
	private static int[][] tomlModels(String text)
	{
		String raw = tomlRaw(text, "models");
		if (raw == null)
		{
			return null;
		}
		java.util.List<int[]> pairs = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]").matcher(raw);
		while (m.find())
		{
			pairs.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
		}
		return pairs.toArray(new int[0][]);
	}

	public ObjectDefManager getObjectDefs()
	{
		return objectDefs;
	}

	// ---- palette lists (for the side-panel browsers) -------------------

	public java.util.Collection<UnderlayDefinition> getUnderlays()
	{
		return underlayManager != null ? underlayManager.getUnderlays() : java.util.Collections.emptyList();
	}

	public java.util.Collection<OverlayDefinition> getOverlays()
	{
		java.util.List<OverlayDefinition> all = new java.util.ArrayList<>();
		if (overlayManager != null)
		{
			all.addAll(overlayManager.getOverlays());
		}
		all.addAll(customOverlays.values());
		return all;
	}

	public java.util.List<TextureDefinition> getTextures()
	{
		return textureManager != null ? textureManager.getTextures() : java.util.Collections.emptyList();
	}

	public java.util.Collection<ObjectDefinition> getAllObjects()
	{
		return objectDefs != null ? objectDefs.getObjects() : java.util.Collections.emptyList();
	}

	public java.util.Collection<NpcDefinition> getAllNpcs()
	{
		return npcDefs != null ? npcDefs.getNpcs() : java.util.Collections.emptyList();
	}

	public ModelManager getModels()
	{
		return models;
	}

	public AnimationManager getAnimations()
	{
		return animations;
	}

	private Boolean animatable;

	/**
	 * Whether this cache's models carry the vertex-group skin data that
	 * ModelDefinition.animate needs. Sampled once. Many repacked/high-rev caches
	 * load models without it, in which case animation can't deform anything.
	 */
	public boolean hasAnimatableModels()
	{
		if (animatable != null)
		{
			return animatable;
		}
		boolean found = false;
		if (models != null && animations != null)
		{
			for (int id = 0; id < 20000 && !found; id += 17)
			{
				net.runelite.cache.definitions.ModelDefinition m = models.get(id);
				if (m != null && m.packedVertexGroups != null)
				{
					found = true;
				}
			}
		}
		animatable = found;
		return found;
	}

	public NpcDefinition getNpc(int id)
	{
		return npcDefs == null ? null : npcDefs.get(id);
	}

	public boolean hasTextures()
	{
		return textureManager != null && spriteManager != null;
	}

	/** Decoded RGBA image for a cache sprite id (frame 0), or null if unavailable. */
	public java.awt.image.BufferedImage getSpriteImage(int spriteId)
	{
		if (spriteManager == null)
		{
			return null;
		}
		try
		{
			net.runelite.cache.definitions.SpriteDefinition sd = spriteManager.findSprite(spriteId, 0);
			return sd == null ? null : spriteManager.getSpriteImage(sd);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private net.runelite.cache.definitions.SpriteDefinition[] mapScenes;
	private boolean mapScenesLoaded;
	private final java.util.Map<Integer, java.awt.image.BufferedImage> mapSceneImageCache = new java.util.HashMap<>();

	/**
	 * Decoded minimap decoration ("map scene") sprite for a given object {@code map_scene} id, or
	 * null. These are the bank/altar/anvil/general-store/etc icons the client stamps wherever an
	 * object carrying that map_scene id sits. Loaded once from the SPRITES index "mapscene" archive.
	 */
	public java.awt.image.BufferedImage getMapSceneImage(int mapSceneId)
	{
		if (mapSceneId < 0)
		{
			return null;
		}
		if (mapSceneImageCache.containsKey(mapSceneId))
		{
			return mapSceneImageCache.get(mapSceneId);
		}
		if (!mapScenesLoaded)
		{
			mapScenesLoaded = true;
			try
			{
				net.runelite.cache.fs.Index index = store.getIndex(IndexType.SPRITES);
				Archive a = index.findArchiveByName("mapscene");
				if (a != null)
				{
					byte[] contents = a.decompress(storage.loadArchive(a));
					mapScenes = new net.runelite.cache.definitions.loaders.SpriteLoader()
						.load(a.getArchiveId(), contents);
				}
			}
			catch (Exception ex)
			{
				mapScenes = null;
			}
		}
		java.awt.image.BufferedImage img = null;
		if (mapScenes != null && mapSceneId < mapScenes.length && mapScenes[mapSceneId] != null)
		{
			try
			{
				img = spriteManager.getSpriteImage(mapScenes[mapSceneId]);
			}
			catch (RuntimeException ex)
			{
				img = null;
			}
		}
		mapSceneImageCache.put(mapSceneId, img);
		return img;
	}

	private java.util.List<net.runelite.cache.definitions.WorldMapElementDefinition> worldMapElements;
	private boolean worldMapLoaded;
	private net.runelite.cache.AreaManager areaManager;
	private boolean areaManagerLoaded;
	private final java.util.Map<Integer, java.awt.image.BufferedImage> areaIconCache = new java.util.HashMap<>();

	/**
	 * All world-map elements (the fullscreen World Map icons): each is an area-definition id + a
	 * world position + a members-only flag. Loaded once from the WORLDMAP index "compositemap"
	 * archive. These are the {@code icons = [[areaId, [x,y,z], flag], …]} entries in the worldmap
	 * area TOMLs. Never null after the first call (empty if the cache has none).
	 */
	public java.util.List<net.runelite.cache.definitions.WorldMapElementDefinition> getWorldMapElements()
	{
		if (!worldMapLoaded)
		{
			worldMapLoaded = true;
			worldMapElements = new java.util.ArrayList<>();
			try
			{
				net.runelite.cache.fs.Index index = store.getIndex(IndexType.WORLDMAP);
				Archive a = index.findArchiveByName("compositemap");
				if (a != null)
				{
					net.runelite.cache.fs.ArchiveFiles files = a.getFiles(storage.loadArchive(a));
					net.runelite.cache.definitions.loaders.WorldMapCompositeLoader loader =
						new net.runelite.cache.definitions.loaders.WorldMapCompositeLoader();
					for (net.runelite.cache.fs.FSFile f : files.getFiles())
					{
						net.runelite.cache.definitions.WorldMapCompositeDefinition c =
							loader.load(f.getContents());
						worldMapElements.addAll(c.getWorldMapElementDefinitions());
					}
				}
			}
			catch (Exception ex)
			{
				// leave the (empty) list — feature just shows nothing
			}
		}
		return worldMapElements;
	}

	/** Icon sprite for a world-map element's area-definition id (via its spriteId), or null. */
	public java.awt.image.BufferedImage getWorldMapIcon(int areaDefinitionId)
	{
		if (areaIconCache.containsKey(areaDefinitionId))
		{
			return areaIconCache.get(areaDefinitionId);
		}
		java.awt.image.BufferedImage img = null;
		try
		{
			if (!areaManagerLoaded)
			{
				areaManagerLoaded = true;
				areaManager = new net.runelite.cache.AreaManager(store);
				areaManager.load();
			}
			if (areaManager != null)
			{
				net.runelite.cache.definitions.AreaDefinition area = areaManager.getArea(areaDefinitionId);
				if (area != null && area.spriteId >= 0)
				{
					net.runelite.cache.definitions.SpriteDefinition sd =
						spriteManager.findSprite(area.spriteId, 0);
					if (sd != null)
					{
						img = spriteManager.getSpriteImage(sd);
					}
				}
			}
		}
		catch (Exception ex)
		{
			img = null;
		}
		areaIconCache.put(areaDefinitionId, img);
		return img;
	}

	// Server-side object spawns parsed from the Reason server source. Each entry is
	// {id, x, y, plane, type, rotation}. These are placed at runtime by the server (NOT in the
	// cache), so the map editor reads them straight from the source files to overlay them.
	private java.util.List<int[]> serverSpawns;

	// Regex for each server file that spawns objects, matched against source lines. Group order
	// must be id, x, y, plane(z), type, rotation.
	//  - HomeHandler.java: GameObject.spawn(id, x, y, z, type, rot)
	// (ClientObj.java is intentionally NOT parsed — in this server its register() blocks are entirely
	// commented out, so nothing there is actually spawned.)
	private static final String GAMEOBJECT_SPAWN_RE =
		"GameObject\\.spawn\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)";

	/** Locates a server source file by walking up from the cache dir to the project root. */
	private File locateServerFile(String relPath)
	{
		File p = cacheDir;
		for (int i = 0; i < 6 && p != null; i++)
		{
			File c = new File(p, relPath);
			if (c.isFile())
			{
				return c;
			}
			p = p.getParentFile();
		}
		return null;
	}

	/**
	 * Parses one server source file for object spawns matching {@code regex} into {@link #serverSpawns},
	 * skipping anything inside {@code /}{@code * … *}{@code /} block comments or after a {@code //} line
	 * comment — so commented-out spawns are never treated as live.
	 */
	private void parseSpawnFile(String relPath, String regex)
	{
		File f = locateServerFile(relPath);
		if (f == null)
		{
			return;
		}
		java.util.regex.Pattern pat = java.util.regex.Pattern.compile(regex);
		try
		{
			boolean inBlock = false;
			for (String line : java.nio.file.Files.readAllLines(f.toPath()))
			{
				String work = line;
				// If we're inside a /* */ block, skip until it closes on this line.
				if (inBlock)
				{
					int end = work.indexOf("*/");
					if (end < 0)
					{
						continue;
					}
					work = work.substring(end + 2);
					inBlock = false;
				}
				// Remove any inline /* */ pairs; an unclosed /* opens a block for later lines.
				while (true)
				{
					int open = work.indexOf("/*");
					if (open < 0)
					{
						break;
					}
					int close = work.indexOf("*/", open + 2);
					if (close < 0)
					{
						work = work.substring(0, open);
						inBlock = true;
						break;
					}
					work = work.substring(0, open) + " " + work.substring(close + 2);
				}
				// Strip a trailing // line comment.
				int lc = work.indexOf("//");
				if (lc >= 0)
				{
					work = work.substring(0, lc);
				}
				java.util.regex.Matcher m = pat.matcher(work);
				while (m.find())
				{
					serverSpawns.add(new int[]{
						Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
						Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
						Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))});
				}
			}
		}
		catch (Exception ex)
		{
			// leave whatever parsed so far
		}
	}

	/**
	 * Server-spawned objects parsed from the server source — the live {@code GameObject.spawn(...)}
	 * calls in HomeHandler.java (bank chest, chests, singing bowl, altar, POH portal, upgrade
	 * stations, …). Each entry is {id, x, y, plane, type, rotation}. Commented-out code (line and
	 * {@code /}{@code * *}{@code /} block comments) is skipped. Never null.
	 */
	public java.util.List<int[]> getServerSpawns()
	{
		if (serverSpawns != null)
		{
			return serverSpawns;
		}
		serverSpawns = new java.util.ArrayList<>();
		parseSpawnFile("kronos-server/src/main/java/io/ruin/model/content/HomeHandler.java", GAMEOBJECT_SPAWN_RE);
		return serverSpawns;
	}

	/** Baked RGB pixels (TEX_SIZE x TEX_SIZE) for a texture id, or null. */
	public int[] getTexturePixels(int textureId)
	{
		if (!hasTextures())
		{
			return null;
		}
		if (texturePixels.containsKey(textureId))
		{
			return texturePixels.get(textureId);
		}
		int[] pixels = null;
		try
		{
			TextureDefinition td = textureManager.findTexture(textureId);
			if (td != null && td.method2680(0.8, TEX_SIZE, spriteManager))
			{
				pixels = td.pixels;
			}
		}
		catch (RuntimeException ex)
		{
			pixels = null;
		}
		texturePixels.put(textureId, pixels);
		return pixels;
	}

	/** Average RGB of a texture, for flat-shaded fallbacks (e.g. terrain). */
	public int getTextureAverage(int textureId)
	{
		Integer cached = textureAverage.get(textureId);
		if (cached != null)
		{
			return cached;
		}
		int avg = 0x808080;
		int[] px = getTexturePixels(textureId);
		if (px != null && px.length > 0)
		{
			long r = 0, g = 0, b = 0;
			int n = 0;
			for (int p : px)
			{
				if ((p & 0xFFFFFF) == 0)
				{
					continue; // skip transparent/black
				}
				r += (p >> 16) & 0xFF;
				g += (p >> 8) & 0xFF;
				b += p & 0xFF;
				n++;
			}
			if (n > 0)
			{
				avg = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
			}
		}
		textureAverage.put(textureId, avg);
		return avg;
	}

	// ---- region discovery / loading ------------------------------------

	/**
	 * Latest OSRS caches (rev ~230+) reorganised index 5: instead of two NAMED archives per region
	 * ({@code m{x}_{y}} terrain + {@code l{x}_{y}} locations, locations XTEA-encrypted), there is ONE
	 * unnamed archive per region keyed by the region id ({@code x<<8|y}), holding terrain as file 0,
	 * locations as file 1, with no encryption. We detect it by the absence of archive names.
	 */
	private Boolean newMapFormat;

	private boolean newMapFormat()
	{
		if (newMapFormat == null)
		{
			boolean anyNamed = false;
			for (Archive a : mapIndex.getArchives())
			{
				if (a.getNameHash() != 0)
				{
					anyNamed = true;
					break;
				}
			}
			newMapFormat = !anyNamed && !mapIndex.getArchives().isEmpty();
		}
		return newMapFormat;
	}

	/** Region ids (x<<8|y) that currently have a terrain archive, ascending. */
	public List<Integer> listRegions()
	{
		List<Integer> result = new ArrayList<>();
		if (newMapFormat())
		{
			// One archive per region; the archive id IS the region id.
			for (Archive a : mapIndex.getArchives())
			{
				result.add(a.getArchiveId());
			}
		}
		else
		{
			for (Archive a : mapIndex.getArchives())
			{
				Integer region = mapNameHashToRegion.get(a.getNameHash());
				if (region != null)
				{
					result.add(region);
				}
			}
		}
		result.sort(Integer::compareTo);
		return result;
	}

	public boolean regionExists(int regionId)
	{
		int x = regionId >> 8;
		int y = regionId & 0xFF;
		if (newMapFormat())
		{
			return mapIndex.getArchive(regionId) != null;
		}
		return mapIndex.findArchiveByName("m" + x + "_" + y) != null;
	}

	/** Lowest unused region id after the highest existing one (a fresh area). */
	public int nextFreeRegionId()
	{
		java.util.Set<Integer> existing = new java.util.HashSet<>(listRegions());
		int start = existing.isEmpty() ? 0 : java.util.Collections.max(existing) + 1;
		for (int id = start; id <= 0xFFFF; id++)
		{
			if ((id & 0xFF) <= 255 && !existing.contains(id))
			{
				return id;
			}
		}
		for (int id = 0; id <= 0xFFFF; id++) // wrap: fill any gap
		{
			if (!existing.contains(id))
			{
				return id;
			}
		}
		return -1;
	}

	public RegionModel loadRegion(int regionId) throws IOException
	{
		int x = regionId >> 8;
		int y = regionId & 0xFF;

		if (mapIndex == null)
		{
			throw new IOException("This cache has no maps index (index 5).");
		}

		if (newMapFormat())
		{
			return loadRegionNewFormat(regionId, x, y);
		}

		Archive mapArchive = mapIndex.findArchiveByName("m" + x + "_" + y);
		if (mapArchive == null)
		{
			throw new IOException("Region " + regionId + " (m" + x + "_" + y + ") does not exist");
		}

		byte[] raw = storage.loadArchive(mapArchive);
		byte[] terrain = raw == null ? null : mapArchive.decompress(raw);
		if (terrain == null)
		{
			throw new IOException("Region " + regionId + " terrain archive is empty or unreadable "
				+ "(corrupt, or a map format this tool doesn't support).");
		}
		// Detect the terrain encoding once per cache (SHORT for this server's cache,
		// BYTE for standard OSRS caches like "near reality"), then decode with it.
		if (mapFmt == null)
		{
			mapFmt = MapCodec.detect(terrain);
		}
		MapDefinition map = MapCodec.decode(x, y, terrain, mapFmt);

		LocationsDefinition locs = new LocationsDefinition();
		locs.setRegionX(x);
		locs.setRegionY(y);

		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapCompression = mapArchive.getCompression();
		model.mapRevision = mapArchive.getRevision();
		model.mapFmt = mapFmt;

		Archive landArchive = mapIndex.findArchiveByName("l" + x + "_" + y);
		if (landArchive != null)
		{
			// Try the key from the key file first, then fall back to no
			// encryption (many RSPS caches are repacked with XTEA stripped).
			int[] provided = keyProvider.getKey(regionId);
			int[][] candidates = provided != null ? new int[][]{provided, null} : new int[][]{null};

			byte[] rawLand = storage.loadArchive(landArchive);
			for (int[] candidate : candidates)
			{
				try
				{
					byte[] locData = landArchive.decompress(rawLand, candidate);
					if (locData != null)
					{
						locs = new LocationsLoader().load(x, y, locData);
						model = new RegionModel(regionId, map, locs);
						model.mapCompression = mapArchive.getCompression();
						model.mapRevision = mapArchive.getRevision();
						model.locCompression = landArchive.getCompression();
						model.locRevision = landArchive.getRevision();
						model.locKeys = candidate;
						model.hasLocationArchive = true;
						break;
					}
				}
				catch (IOException | RuntimeException ex)
				{
					// try next candidate
				}
			}
		}

		return model;
	}

	/**
	 * Load a region from the latest OSRS cache layout: one unnamed archive per region (id = x&lt;&lt;8|y)
	 * whose file 0 is the terrain and file 1 is the locations, unencrypted.
	 */
	private RegionModel loadRegionNewFormat(int regionId, int x, int y) throws IOException
	{
		Archive regionArchive = mapIndex.getArchive(regionId);
		if (regionArchive == null)
		{
			throw new IOException("Region " + regionId + " (" + x + "," + y + ") does not exist");
		}

		byte[] raw = storage.loadArchive(regionArchive);
		net.runelite.cache.fs.ArchiveFiles files = regionArchive.getFiles(raw);
		net.runelite.cache.fs.FSFile terrainFile = files.findFile(0);
		byte[] terrain = terrainFile == null ? null : terrainFile.getContents();
		if (terrain == null)
		{
			throw new IOException("Region " + regionId + " has no terrain (file 0).");
		}

		if (mapFmt == null)
		{
			mapFmt = MapCodec.detect(terrain);
		}
		MapDefinition map = MapCodec.decode(x, y, terrain, mapFmt);

		LocationsDefinition locs = new LocationsDefinition();
		locs.setRegionX(x);
		locs.setRegionY(y);

		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapCompression = regionArchive.getCompression();
		model.mapRevision = regionArchive.getRevision();
		model.mapFmt = mapFmt;

		net.runelite.cache.fs.FSFile locFile = files.findFile(1);
		byte[] locData = locFile == null ? null : locFile.getContents();
		if (locData != null)
		{
			locs = new LocationsLoader().load(x, y, locData);
			model = new RegionModel(regionId, map, locs);
			model.mapCompression = regionArchive.getCompression();
			model.mapRevision = regionArchive.getRevision();
			model.mapFmt = mapFmt;
			model.locCompression = regionArchive.getCompression();
			model.locRevision = regionArchive.getRevision();
			model.locKeys = null; // unencrypted in this format
			model.hasLocationArchive = true;
		}
		return model;
	}

	/**
	 * Load a region from loose {@code m{x}_{y}} / {@code l{x}_{y}} files (as written by
	 * {@link #dumpRegion}) instead of from the cache. The region x/y come from the m file's NAME,
	 * and the sibling l file is picked up from the same folder if present.
	 * <p>
	 * The terrain format is detected from the file itself rather than reusing the cache's, since an
	 * imported dump may well come from a different cache. Files are accepted either raw or gzipped,
	 * because other map tools emit the compressed container payload.
	 * <p>
	 * The result is NOT attached to the cache: it has no archives behind it, so saving it writes to
	 * whichever region id the filename implies. Locations are marked read-only when no l file is found.
	 */
	public RegionModel loadRegionFromFiles(File mFile) throws IOException
	{
		// The .dat suffix is optional: we write it, but dumps from other tools (and our own earlier
		// ones) are often extensionless.
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("^m(\\d+)_(\\d+)(?:\\.dat)?$").matcher(mFile.getName());
		if (!m.matches())
		{
			throw new IOException("Expected a file named m{x}_{y}[.dat] (e.g. m48_54.dat), got: "
				+ mFile.getName());
		}
		int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2));
		int regionId = (x << 8) | y;

		byte[] terrain = gunzipIfNeeded(java.nio.file.Files.readAllBytes(mFile.toPath()));
		MapCodec.Fmt fmt = MapCodec.detect(terrain);
		MapDefinition map = MapCodec.decode(x, y, terrain, fmt);

		LocationsDefinition locs = new LocationsDefinition();
		locs.setRegionX(x);
		locs.setRegionY(y);

		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapFmt = fmt;

		// Match the locations file to whichever naming the m file used, but accept either.
		File lFile = new File(mFile.getParentFile(), "l" + x + "_" + y + ".dat");
		if (!lFile.isFile())
		{
			lFile = new File(mFile.getParentFile(), "l" + x + "_" + y);
		}
		if (lFile.isFile())
		{
			byte[] locData = gunzipIfNeeded(java.nio.file.Files.readAllBytes(lFile.toPath()));
			locs = new LocationsLoader().load(x, y, locData);
			model = new RegionModel(regionId, map, locs);
			model.mapFmt = fmt;
			model.hasLocationArchive = true;
		}
		return model;
	}

	/**
	 * Count tiles that cannot survive a BYTE-format encode. The byte encoder masks overlay ids to
	 * 0xFF, and writes underlay as {@code id + 81} in one byte (so ids above 174 wrap) — in both
	 * cases silently, producing a file that loads but renders with the wrong ground.
	 *
	 * @return {@code {overlayOverflows, underlayOverflows}}; all zero means the region round-trips
	 * through BYTE format losslessly.
	 */
	public static int[] countByteFmtOverflows(MapDefinition map)
	{
		int overlay = 0, underlay = 0;
		MapDefinition.Tile[][][] tiles = map.getTiles();
		for (int z = 0; z < net.runelite.cache.region.Region.Z; z++)
		{
			for (int x = 0; x < net.runelite.cache.region.Region.X; x++)
			{
				for (int y = 0; y < net.runelite.cache.region.Region.Y; y++)
				{
					MapDefinition.Tile t = tiles[z][x][y];
					if (t == null)
					{
						continue;
					}
					if ((t.overlayId & 0xFFFF) > 0xFF)
					{
						overlay++;
					}
					if ((t.underlayId & 0xFFFF) + 81 > 0xFF)
					{
						underlay++;
					}
				}
			}
		}
		return new int[]{overlay, underlay};
	}

	/**
	 * Load a region from one or two loose map files whose names carry no region info — e.g. RSPSi's
	 * {@code 624.dat}/{@code 625.dat}, named after archive ids from ITS cache, which do not match
	 * ours. Terrain and locations are told apart by CONTENT ({@link MapCodec#isTerrain}) rather than
	 * by filename or argument order, so the caller can pass them either way round.
	 *
	 * @param files one or two files; exactly one must look like terrain.
	 * @param regionX region x the caller wants these loaded as — cannot be inferred, as the region
	 * is encoded in neither the filename nor the data.
	 */
	public RegionModel loadRegionFromFiles(File[] files, int regionX, int regionY) throws IOException
	{
		if (files == null || files.length == 0 || files.length > 2)
		{
			throw new IOException("Select one or two files (terrain, and optionally locations).");
		}
		byte[] terrain = null, locData = null;
		for (File f : files)
		{
			byte[] data = gunzipIfNeeded(java.nio.file.Files.readAllBytes(f.toPath()));
			if (MapCodec.isTerrain(data))
			{
				if (terrain != null)
				{
					throw new IOException("Both files look like terrain — expected one terrain "
						+ "and one locations file.");
				}
				terrain = data;
			}
			else
			{
				locData = data;
			}
		}
		if (terrain == null)
		{
			throw new IOException("No terrain file found: none of the selected files decode as a "
				+ "region map. (A locations file alone cannot be opened.)");
		}

		int regionId = (regionX << 8) | regionY;
		MapCodec.Fmt fmt = MapCodec.detect(terrain);
		MapDefinition map = MapCodec.decode(regionX, regionY, terrain, fmt);

		LocationsDefinition locs = new LocationsDefinition();
		locs.setRegionX(regionX);
		locs.setRegionY(regionY);

		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapFmt = fmt;

		if (locData != null)
		{
			locs = new LocationsLoader().load(regionX, regionY, locData);
			model = new RegionModel(regionId, map, locs);
			model.mapFmt = fmt;
			model.hasLocationArchive = true;
		}
		return model;
	}

	/**
	 * One region inside an RSPSi {@code .pack} project file. {@link #gridX}/{@link #gridY} give the
	 * region's slot in the pack's grid; the actual world region is NOT stored anywhere in the file,
	 * so the caller must supply it.
	 */
	public static final class PackEntry
	{
		public final int locArchiveId, mapArchiveId, gridX, gridY;
		public final byte[] locations, terrain;

		PackEntry(int locArchiveId, int mapArchiveId, int gridX, int gridY,
			byte[] locations, byte[] terrain)
		{
			this.locArchiveId = locArchiveId;
			this.mapArchiveId = mapArchiveId;
			this.gridX = gridX;
			this.gridY = gridY;
			this.locations = locations;
			this.terrain = terrain;
		}

		@Override
		public String toString()
		{
			return "grid (" + gridX + "," + gridY + ")  map " + mapArchiveId
				+ " / loc " + locArchiveId + "  —  " + terrain.length + " B terrain, "
				+ locations.length + " B locations";
		}
	}

	/**
	 * Read an RSPSi {@code .pack} project file — its working format for a multi-region map.
	 * Layout (all big-endian, blocks stored UNCOMPRESSED):
	 * <pre>
	 * int count
	 * per region: int locArchiveId, int mapArchiveId, int gridX, int gridY,
	 *             int locLen, byte[locLen] locations,
	 *             int mapLen, byte[mapLen] terrain
	 * </pre>
	 * Note locations precede terrain. Verified against NR_home.pack: 4 regions, every block
	 * decoding exactly with no bytes left over.
	 */
	public static PackEntry[] readPack(File file) throws IOException
	{
		byte[] d = java.nio.file.Files.readAllBytes(file.toPath());
		java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(d));
		int count = in.readInt();
		if (count < 0 || count > 4096)
		{
			throw new IOException("Not an RSPSi .pack file (implausible region count " + count + ").");
		}
		PackEntry[] out = new PackEntry[count];
		for (int i = 0; i < count; i++)
		{
			int locId = in.readInt(), mapId = in.readInt(), gx = in.readInt(), gy = in.readInt();
			byte[] loc = new byte[in.readInt()];
			in.readFully(loc);
			byte[] map = new byte[in.readInt()];
			in.readFully(map);
			out[i] = new PackEntry(locId, mapId, gx, gy, loc, map);
		}
		return out;
	}

	/**
	 * Build an editable region from a pack entry, loaded as region {@code regionX,regionY} — which
	 * the pack does not record, so the caller chooses it.
	 */
	public RegionModel regionFromPack(PackEntry e, int regionX, int regionY) throws IOException
	{
		if (!MapCodec.isTerrain(e.terrain))
		{
			throw new IOException("Pack entry's terrain block does not decode as a region map.");
		}
		int regionId = (regionX << 8) | regionY;
		MapCodec.Fmt fmt = MapCodec.detect(e.terrain);
		MapDefinition map = MapCodec.decode(regionX, regionY, e.terrain, fmt);

		LocationsDefinition locs = new LocationsLoader().load(regionX, regionY, e.locations);
		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapFmt = fmt;
		model.hasLocationArchive = true;
		return model;
	}

	/** Loose map files may be raw or gzipped depending on which tool wrote them. */
	private static byte[] gunzipIfNeeded(byte[] data) throws IOException
	{
		if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B)
		{
			return data;
		}
		try (java.util.zip.GZIPInputStream in =
				 new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data)))
		{
			return in.readAllBytes();
		}
	}

	// ---- saving --------------------------------------------------------

	public void saveRegion(RegionModel model) throws IOException
	{
		int regionId = model.getRegionId();
		int x = model.getRegionX();
		int y = model.getRegionY();

		// Terrain (never encrypted).
		Archive mapArchive = mapIndex.findArchiveByName("m" + x + "_" + y);
		byte[] terrain = MapCodec.encode(model.getMap(), model.mapFmt);
		writeArchive(mapArchive, terrain, null,
			model.mapCompression != 0 ? model.mapCompression : CompressionType.GZ);

		// Locations. Write with the same keys they were read with: null means the
		// cache is unencrypted, which is how this (and most RSPS) caches store them.
		int[] keys = model.locKeys;
		if (keys != null && !keyProvider.hasKey(regionId))
		{
			// Encrypted cache + new square: register the key so the server serves it.
			keyProvider.putKey(regionId, keys);
			keyProvider.save();
		}

		Archive landArchive = mapIndex.findArchiveByName("l" + x + "_" + y);
		if (landArchive == null)
		{
			landArchive = createArchive(mapIndex, "l" + x + "_" + y);
		}
		byte[] locData = new LocationsSaver().save(model.getLocations());
		writeArchive(landArchive, locData, keys,
			model.locCompression != 0 ? model.locCompression : CompressionType.GZ);

		// Only rewrite the MAPS index reference table — NOT every index. Rewriting read-only index
		// tables (sprites/textures/models) is what desyncs their CRC and corrupts the cache on close.
		store.save(mapIndex);
		model.clearDirty();
	}

	/**
	 * Dump a region's two cache archives to loose files in {@code dir}, as {@code m{x}_{y}} and
	 * {@code l{x}_{y}}. These are the DECOMPRESSED archive payloads — the same bytes
	 * {@link #saveRegion} hands to the container before GZ/BZ2 compression — which is what map
	 * tooling expects for loose m/l files. Dumps the current in-editor state, so unsaved edits
	 * are included. Read-only: touches neither the cache nor the model.
	 *
	 * @return the two files written, terrain first.
	 */
	public File[] dumpRegion(RegionModel model, File dir) throws IOException
	{
		return dumpRegion(model, dir, model.mapFmt);
	}

	/**
	 * As {@link #dumpRegion(RegionModel, File)}, but writes the terrain in an explicit format.
	 * Use {@link MapCodec.Fmt#BYTE} for vanilla-format tools such as RSPSi, which read each tile
	 * attribute as a single byte and will run off the end of a SHORT-format buffer.
	 * <p>
	 * BYTE is narrower than this server's native format — check {@link #countByteFmtOverflows}
	 * first, as ids outside its range are silently truncated by the encoder.
	 */
	public File[] dumpRegion(RegionModel model, File dir, MapCodec.Fmt fmt) throws IOException
	{
		return dumpRegion(model, dir, fmt, false);
	}

	/**
	 * As above, but {@code byArchiveId} names the files after their index-5 ARCHIVE ID
	 * ({@code 624.dat}) instead of {@code m{x}_{y}.dat}. RSPSi identifies map files by archive id,
	 * not by name — given m/l names it cannot tell terrain from locations and will feed the wrong
	 * one to its terrain decoder.
	 * <p>
	 * The ids come from THIS cache's index. They only line up with another cache's if both derive
	 * from the same build, so a mismatch means the receiving tool loads a different region.
	 */
	public File[] dumpRegion(RegionModel model, File dir, MapCodec.Fmt fmt, boolean byArchiveId)
		throws IOException
	{
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("Could not create " + dir);
		}
		int x = model.getRegionX(), y = model.getRegionY();

		String mName = "m" + x + "_" + y, lName = "l" + x + "_" + y;
		if (byArchiveId)
		{
			Archive ma = mapIndex == null ? null : mapIndex.findArchiveByName(mName);
			Archive la = mapIndex == null ? null : mapIndex.findArchiveByName(lName);
			if (ma == null || la == null)
			{
				throw new IOException("Cannot dump by archive id: this region has no "
					+ (ma == null ? mName : lName) + " archive in the open cache.");
			}
			mName = String.valueOf(ma.getArchiveId());
			lName = String.valueOf(la.getArchiveId());
		}

		File mFile = new File(dir, mName + ".dat");
		java.nio.file.Files.write(mFile.toPath(), MapCodec.encode(model.getMap(), fmt));

		File lFile = new File(dir, lName + ".dat");
		java.nio.file.Files.write(lFile.toPath(), new LocationsSaver().save(model.getLocations()));

		return new File[]{mFile, lFile};
	}

	private void writeArchive(Archive archive, byte[] data, int[] keys, int compression) throws IOException
	{
		int revision = archive.getRevision() + 1;
		Container container = new Container(compression, revision);
		container.compress(data, keys);

		archive.setRevision(revision);
		archive.setCompression(compression);
		archive.setCrc(container.crc);
		archive.setDecompressedSize(data.length);
		archive.setCompressedSize(container.data.length);

		storage.saveArchive(archive, container.data);
	}

	// ---- creating new map squares --------------------------------------

	public RegionModel addRegion(int regionX, int regionY) throws IOException
	{
		int regionId = (regionX << 8) | regionY;
		if (regionExists(regionId))
		{
			throw new IOException("Region " + regionX + "," + regionY + " already exists");
		}

		MapDefinition map = new MapDefinition();
		map.setRegionX(regionX);
		map.setRegionY(regionY);

		LocationsDefinition locs = new LocationsDefinition();
		locs.setRegionX(regionX);
		locs.setRegionY(regionY);

		// Create the terrain archive up front so findArchiveByName works.
		createArchive(mapIndex, "m" + regionX + "_" + regionY);

		RegionModel model = new RegionModel(regionId, map, locs);
		model.mapCompression = CompressionType.GZ;
		model.locCompression = CompressionType.GZ;
		model.hasLocationArchive = true;
		model.mapFmt = mapFmt != null ? mapFmt : MapCodec.Fmt.SHORT;

		// Persist immediately (writes terrain + empty locations + xtea key).
		saveRegion(model);
		return model;
	}

	private Archive createArchive(Index index, String name)
	{
		int newId = 0;
		for (Archive a : index.getArchives())
		{
			if (a.getArchiveId() >= newId)
			{
				newId = a.getArchiveId() + 1;
			}
		}

		Archive archive = index.addArchive(newId);
		archive.setNameHash(Djb2.hash(name));
		archive.setRevision(0);

		FileData fileData = new FileData();
		fileData.setId(0);
		fileData.setNameHash(0);
		archive.setFileData(new FileData[]{fileData});

		return archive;
	}
}
