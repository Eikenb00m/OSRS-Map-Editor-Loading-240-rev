package net.runelite.cache.editor;

import java.io.File;
import net.runelite.cache.IndexType;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.Djb2;

/** Diagnose a cache's map index (5): are archives named? Args: cacheDir. */
public final class CacheProbe
{
	public static void main(String[] args) throws Exception
	{
		try (Store store = new Store(new File(args[0])))
		{
			store.load();
			Index maps = store.getIndex(IndexType.MAPS);
			if (maps == null)
			{
				System.out.println("index 5 (MAPS): MISSING");
				return;
			}
			java.util.List<Archive> archives = maps.getArchives();
			int named = 0, unnamed = 0;
			for (Archive a : archives)
			{
				if (a.getNameHash() != 0) named++; else unnamed++;
			}
			System.out.println("index 5 archives=" + archives.size() + "  withNameHash=" + named + "  noNameHash=" + unnamed);
			System.out.println("first 8 archives (id : nameHash):");
			for (int i = 0; i < Math.min(8, archives.size()); i++)
			{
				Archive a = archives.get(i);
				System.out.println("  id=" + a.getArchiveId() + " nameHash=" + a.getNameHash());
			}
			System.out.println("Djb2(\"m50_50\")=" + Djb2.hash("m50_50"));
			System.out.println("findArchiveByName(\"m50_50\") -> " + maps.findArchiveByName("m50_50"));
			System.out.println("findArchiveByName(\"l50_50\") -> " + maps.findArchiveByName("l50_50"));
			// If unnamed, is there a plausible archive-id convention? show id range.
			int min = Integer.MAX_VALUE, max = -1;
			for (Archive a : archives) { min = Math.min(min, a.getArchiveId()); max = Math.max(max, a.getArchiveId()); }
			System.out.println("archiveId range: " + min + ".." + max);

			// Hypothesis: archiveId == regionId (x<<8|y). Test region 50,50 = 12850.
			Archive a12850 = maps.getArchive(12850);
			System.out.println("getArchive(12850) [region 50,50] -> " + (a12850 == null ? "null" : "EXISTS"));
			// How many archive ids are valid region ids (x<0..255, y 0..255 both plausible)?
			int looksLikeRegion = 0;
			for (Archive a : archives)
			{
				int id = a.getArchiveId();
				int rx = id >> 8, ry = id & 0xFF;
				if (rx >= 0 && rx <= 255 && ry >= 0 && ry <= 255) looksLikeRegion++;
			}
			System.out.println(looksLikeRegion + " / " + archives.size() + " archive ids fit x<<8|y");
			if (a12850 != null)
			{
				byte[] data = store.getStorage().loadArchive(a12850);
				net.runelite.cache.fs.ArchiveFiles files = a12850.getFiles(data);
				System.out.println("archive 12850 files: " + files.getFiles().size());
				for (net.runelite.cache.fs.FSFile f : files.getFiles())
				{
					System.out.println("  fileId=" + f.getFileId() + " nameHash=" + f.getNameHash() + " len=" + (f.getContents() == null ? -1 : f.getContents().length));
				}
			}

			// Map EVERY unhandled object opcode + its following bytes, across all objects.
			Index cfg = store.getIndex(IndexType.CONFIGS);
			Archive objArchive = cfg.getArchive(net.runelite.cache.ConfigType.OBJECT.getId());
			net.runelite.cache.fs.ArchiveFiles objFiles = objArchive.getFiles(store.getStorage().loadArchive(objArchive));
			net.runelite.cache.definitions.loaders.ObjectLoader ldr = new net.runelite.cache.definitions.loaders.ObjectLoader();
			System.setProperty("reOpcode", "1");
			java.util.Map<Integer, Integer> unkCount = new java.util.TreeMap<>();
			java.util.Map<Integer, String> unkSample = new java.util.HashMap<>();
			int scanned = 0;
			for (net.runelite.cache.fs.FSFile f : objFiles.getFiles())
			{
				byte[] b = f.getContents();
				if (b == null || b.length < 2) continue;
				scanned++;
				try { ldr.load(f.getFileId(), b); }
				catch (IllegalStateException ex)
				{
					String msg = ex.getMessage();
					if (msg == null || !msg.startsWith("UNKOP")) continue;
					String[] p = msg.split(" ");
					int op = Integer.parseInt(p[1]), off = Integer.parseInt(p[2]);
					unkCount.merge(op, 1, Integer::sum);
					if (!unkSample.containsKey(op))
					{
						StringBuilder hx = new StringBuilder();
						for (int k = off; k < Math.min(off + 10, b.length); k++) hx.append(String.format("%02X ", b[k] & 0xFF));
						unkSample.put(op, "obj " + f.getFileId() + " nextBytes[" + off + "]: " + hx);
					}
				}
				catch (Throwable ignored) {}
			}
			System.clearProperty("reOpcode");
			System.out.println("\n=== unhandled object opcodes (scanned " + scanned + ") ===");
			for (java.util.Map.Entry<Integer, Integer> e : unkCount.entrySet())
			{
				System.out.println("op " + e.getKey() + "  count=" + e.getValue() + "  " + unkSample.get(e.getKey()));
			}

			// Gather many op-6 and op-7 contexts to deduce their byte layout.
			for (int targetOp : new int[]{93, 95, 96, 101})
			{
				System.out.println("\n=== op " + targetOp + " contexts (opcode byte + next 12) ===");
				System.setProperty("reOpcode", "1");
				int got = 0;
				for (net.runelite.cache.fs.FSFile f : objFiles.getFiles())
				{
					byte[] b = f.getContents();
					if (b == null || b.length < 2) continue;
					try { ldr.load(f.getFileId(), b); }
					catch (IllegalStateException ex)
					{
						String msg = ex.getMessage();
						if (msg == null || !msg.startsWith("UNKOP")) continue;
						String[] p = msg.split(" ");
						int op = Integer.parseInt(p[1]), off = Integer.parseInt(p[2]);
						if (op != targetOp) continue;
						StringBuilder hx = new StringBuilder();
						for (int k = off; k < Math.min(off + 12, b.length); k++) hx.append(String.format("%02X ", b[k] & 0xFF));
						System.out.println("  obj " + f.getFileId() + " @" + off + ": " + hx + " (len " + b.length + ")");
						if (++got >= 12) break;
					}
					catch (Throwable ignored) {}
				}
				System.clearProperty("reOpcode");
			}

			// Why do textures/sprites fail? Test each separately with a full stack trace.
			System.out.println("\n=== sprites / textures ===");
			try { net.runelite.cache.SpriteManager sm = new net.runelite.cache.SpriteManager(store); sm.load(); System.out.println("sprites OK"); }
			catch (Throwable t) { System.out.println("sprites FAILED: " + t); StackTraceElement[] st = t.getStackTrace(); for (int i = 0; i < Math.min(6, st.length); i++) System.out.println("  at " + st[i]); }
			try { net.runelite.cache.TextureManager tm = new net.runelite.cache.TextureManager(store); tm.load(); System.out.println("textures OK: " + tm.getTextures().size()); }
			catch (Throwable t) { System.out.println("textures FAILED: " + t); StackTraceElement[] st = t.getStackTrace(); for (int i = 0; i < Math.min(6, st.length); i++) System.out.println("  at " + st[i]); }
			// Dump raw texture defs to see the new format.
			Index texIdx = store.getIndex(IndexType.TEXTURES);
			Archive texArch = texIdx.getArchive(0);
			net.runelite.cache.fs.ArchiveFiles texFiles = texArch.getFiles(store.getStorage().loadArchive(texArch));
			java.util.Map<Integer, Integer> lenDist = new java.util.TreeMap<>();
			int td = 0;
			for (net.runelite.cache.fs.FSFile f : texFiles.getFiles())
			{
				byte[] b = f.getContents();
				lenDist.merge(b.length, 1, Integer::sum);
				if (td < 16)
				{
					StringBuilder hx = new StringBuilder();
					for (int k = 0; k < Math.min(40, b.length); k++) hx.append(String.format("%02X ", b[k] & 0xFF));
					int sprId = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
					System.out.println("  tex " + f.getFileId() + " len=" + b.length + " sprId?=" + sprId + ": " + hx);
					td++;
				}
			}
			System.out.println("  texture count=" + texFiles.getFiles().size() + " lengthDist=" + lenDist);
		}

		// End-to-end: does the patched loader open this cache now?
		try
		{
			MapEditorService svc = new MapEditorService(new File(args[0]), new JsonXteaKeyProvider(null));
			svc.open();
			java.util.List<Integer> regions = svc.listRegions();
			System.out.println("\nlistRegions() -> " + regions.size() + " regions");
			RegionModel rm = svc.loadRegion(12850);
			System.out.println("loadRegion(12850) -> " + (rm != null ? "OK  hasLocs=" + rm.hasLocationArchive : "null"));

			// Do this region's object models actually decode? (3D objects need them)
			ModelManager mm = svc.getModels();
			System.out.println("getModels() -> " + (mm == null ? "NULL (3D disabled)" : "present"));
			System.out.println("getObjectDefs() -> " + (svc.getObjectDefs() == null ? "NULL" : "present"));
			if (mm != null && rm != null)
			{
				System.out.println("locations loaded: " + rm.getLocations().getLocations().size());
				int withModels = 0, nullModels = 0, nullDef = 0;
				java.util.Set<Integer> seenObj = new java.util.HashSet<>();
				for (net.runelite.cache.region.Location loc : rm.getLocations().getLocations())
				{
					if (!seenObj.add(loc.getId())) continue;
					net.runelite.cache.definitions.ObjectDefinition d = svc.getObject(loc.getId());
					if (d == null) { nullDef++; continue; }
					if (d.getObjectModels() != null && d.getObjectModels().length > 0) withModels++;
					else nullModels++;
				}
				System.out.println("distinct objects=" + seenObj.size() + "  withModels=" + withModels
					+ "  nullModels=" + nullModels + "  nullDef=" + nullDef);
				java.util.Set<Integer> mids = new java.util.TreeSet<>();
				for (net.runelite.cache.region.Location loc : rm.getLocations().getLocations())
				{
					net.runelite.cache.definitions.ObjectDefinition d = svc.getObject(loc.getId());
					if (d != null && d.getObjectModels() != null)
					{
						for (int mid : d.getObjectModels()) mids.add(mid);
					}
				}
				int ok = 0, fail = 0;
				Integer firstFail = null;
				for (int mid : mids)
				{
					if (mm.get(mid) != null) ok++; else { fail++; if (firstFail == null) firstFail = mid; }
				}
				System.out.println("object models in region: " + mids.size() + "  decoded=" + ok + "  FAILED=" + fail
					+ (firstFail != null ? "  firstFail=" + firstFail : ""));
				SceneBuilder sb = new SceneBuilder(svc);
				Renderer3D.Scene objScene = sb.buildObjectsOnly(rm, 0);
				System.out.println("3D OBJECT TRIANGLES (plane 0): " + objScene.size());
			}
		}
		catch (Throwable t)
		{
			System.out.println("\nloadRegion FAILED: " + t);
			t.printStackTrace();
		}
	}
}
