/*
 * Loads server-side spawns (which are NOT in the cache) so they can be overlaid
 * on the map:
 *   NPCs    -> data/npcs/spawns/*.json      {id,x,y,z,direction,npcName}
 *   Objects -> data/objects/spawns/*.json   {id,x,y,z,type}   (may contain // comments)
 *
 * Coordinates are absolute world tiles. Files are parsed leniently: line and
 * block comments and trailing commas are stripped before parsing.
 */
package net.runelite.cache.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class SpawnLoader
{
	public static class Spawn
	{
		public final boolean npc;
		public final int id;
		public final int x, y, z;
		public final int type;        // objects only
		public final int orientation; // 0..3, from npc facing direction
		public final String name;

		Spawn(boolean npc, int id, int x, int y, int z, int type, int orientation, String name)
		{
			this.npc = npc; this.id = id; this.x = x; this.y = y; this.z = z;
			this.type = type; this.orientation = orientation; this.name = name;
		}

		public int regionId()
		{
			return ((x >> 6) << 8) | (y >> 6);
		}
	}

	private final List<Spawn> spawns = new ArrayList<>();

	/** dataDir is the server's data/ folder (parent of the cache folder). */
	public void load(File dataDir)
	{
		if (dataDir == null)
		{
			return;
		}
		loadDir(new File(dataDir, "npcs/spawns"), true);
		loadDir(new File(dataDir, "objects/spawns"), false);
	}

	public List<Spawn> getSpawns()
	{
		return spawns;
	}

	public int size()
	{
		return spawns.size();
	}

	private void loadDir(File dir, boolean npc)
	{
		File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
		if (files == null)
		{
			return;
		}
		for (File f : files)
		{
			try
			{
				parse(f, npc);
			}
			catch (Exception ex)
			{
				System.err.println("Spawn file skipped " + f.getName() + ": " + ex.getMessage());
			}
		}
	}

	private void parse(File file, boolean npc) throws IOException
	{
		String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		text = stripComments(text);

		JsonElement root = new JsonParser().parse(text);
		if (!root.isJsonArray())
		{
			return;
		}
		JsonArray arr = root.getAsJsonArray();
		Gson gson = new Gson();
		for (JsonElement el : arr)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			int id = intOr(o, "id", -1);
			int x = intOr(o, "x", -1);
			int y = intOr(o, "y", -1);
			if (id < 0 || x < 0 || y < 0)
			{
				continue;
			}
			int z = intOr(o, "z", 0);
			int type = intOr(o, "type", 10);
			String name = o.has("npcName") ? o.get("npcName").getAsString()
				: (o.has("name") ? o.get("name").getAsString() : null);
			int orientation = o.has("direction") && o.get("direction").isJsonPrimitive()
				? directionToOrientation(o.get("direction").getAsString()) : 0;
			spawns.add(new Spawn(npc, id, x, y, z, type, orientation, name));
		}
	}

	private static int directionToOrientation(String dir)
	{
		if (dir == null)
		{
			return 0;
		}
		switch (dir.trim().toUpperCase())
		{
			case "S": return 0;
			case "W": return 1;
			case "N": return 2;
			case "E": return 3;
			default: return 0;
		}
	}

	private static int intOr(JsonObject o, String key, int def)
	{
		try
		{
			return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
		}
		catch (RuntimeException ex)
		{
			return def;
		}
	}

	/** Removes // line comments, block comments and trailing commas. */
	private static String stripComments(String s)
	{
		StringBuilder out = new StringBuilder(s.length());
		boolean inString = false, inLine = false, inBlock = false;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
			if (inLine)
			{
				if (c == '\n') { inLine = false; out.append(c); }
				continue;
			}
			if (inBlock)
			{
				if (c == '*' && next == '/') { inBlock = false; i++; }
				continue;
			}
			if (inString)
			{
				out.append(c);
				if (c == '\\' && next != '\0') { out.append(next); i++; }
				else if (c == '"') { inString = false; }
				continue;
			}
			if (c == '"') { inString = true; out.append(c); }
			else if (c == '/' && next == '/') { inLine = true; i++; }
			else if (c == '/' && next == '*') { inBlock = true; i++; }
			else { out.append(c); }
		}
		// remove trailing commas: ,] and ,}
		return out.toString().replaceAll(",\\s*([}\\]])", "$1");
	}
}
