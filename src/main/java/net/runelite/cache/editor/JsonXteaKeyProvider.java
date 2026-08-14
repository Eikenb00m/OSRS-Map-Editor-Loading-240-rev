/*
 * XTEA key provider that understands the server's xteas.json, which uses the
 * map form  { "regionId": [k0, k1, k2, k3], ... }  rather than RuneLite's list
 * form. Keys can be added (for freshly created regions) and written back out.
 */
package net.runelite.cache.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.util.KeyProvider;

public class JsonXteaKeyProvider implements KeyProvider
{
	private static final int[] ZERO = new int[]{0, 0, 0, 0};

	private final File file;
	private final Map<Integer, int[]> keys = new TreeMap<>();

	// Preserve whatever was on disk so new keys can be added without discarding
	// any metadata (region_keys.json carries archive/group/name_hash/name).
	private boolean arrayForm;

	public JsonXteaKeyProvider(File file) throws IOException
	{
		this.file = file;
		if (file != null && file.exists())
		{
			load();
		}
	}

	private void load() throws IOException
	{
		try (Reader reader = new FileReader(file))
		{
			JsonElement root = new JsonParser().parse(reader);
			if (root.isJsonArray())
			{
				arrayForm = true;
				loadOpenRs2Array(root.getAsJsonArray());
			}
			else if (root.isJsonObject())
			{
				loadMapForm(root.getAsJsonObject());
			}
		}
	}

	/** xteas.json form: { "regionId": [k0,k1,k2,k3], ... } */
	private void loadMapForm(JsonObject obj)
	{
		Type keyType = new TypeToken<int[]>() { }.getType();
		Gson gson = new Gson();
		for (Map.Entry<String, JsonElement> e : obj.entrySet())
		{
			try
			{
				keys.put(Integer.parseInt(e.getKey().trim()), gson.<int[]>fromJson(e.getValue(), keyType));
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed entries
			}
		}
	}

	/** region_keys.json (OpenRS2) form: [ { "mapsquare": id, "key": [..] }, ... ] */
	private void loadOpenRs2Array(JsonArray array)
	{
		Type keyType = new TypeToken<int[]>() { }.getType();
		Gson gson = new Gson();
		for (JsonElement el : array)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			Integer region = null;
			if (o.has("mapsquare"))
			{
				region = o.get("mapsquare").getAsInt();
			}
			else if (o.has("region"))
			{
				region = o.get("region").getAsInt();
			}
			JsonElement k = o.has("key") ? o.get("key") : o.get("keys");
			if (region != null && k != null)
			{
				keys.put(region, gson.<int[]>fromJson(k, keyType));
			}
		}
	}

	@Override
	public int[] getKey(int region)
	{
		return keys.get(region);
	}

	/** Returns the region's key, or all-zero keys if none is known. */
	public int[] getKeyOrZero(int region)
	{
		int[] k = keys.get(region);
		return k != null ? k : ZERO.clone();
	}

	public boolean hasKey(int region)
	{
		return keys.containsKey(region);
	}

	public void putKey(int region, int[] key)
	{
		keys.put(region, key);
	}

	public void save() throws IOException
	{
		if (file == null)
		{
			return;
		}
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		if (arrayForm)
		{
			// Re-read the file and add/update entries in place so existing
			// metadata (name_hash, name, archive, group) is preserved.
			JsonArray array = new JsonArray();
			if (file.exists())
			{
				try (Reader reader = new FileReader(file))
				{
					JsonElement root = new JsonParser().parse(reader);
					if (root.isJsonArray())
					{
						array = root.getAsJsonArray();
					}
				}
			}
			Map<Integer, JsonObject> byRegion = new java.util.HashMap<>();
			for (JsonElement el : array)
			{
				if (el.isJsonObject())
				{
					JsonObject o = el.getAsJsonObject();
					if (o.has("mapsquare"))
					{
						byRegion.put(o.get("mapsquare").getAsInt(), o);
					}
					else if (o.has("region"))
					{
						byRegion.put(o.get("region").getAsInt(), o);
					}
				}
			}
			for (Map.Entry<Integer, int[]> e : keys.entrySet())
			{
				JsonObject o = byRegion.get(e.getKey());
				JsonArray k = new JsonArray();
				for (int v : e.getValue())
				{
					k.add(v);
				}
				if (o == null)
				{
					o = new JsonObject();
					o.addProperty("mapsquare", e.getKey());
					o.add("key", k);
					array.add(o);
				}
				else
				{
					o.add(o.has("key") ? "key" : "keys", k);
				}
			}
			try (Writer writer = new FileWriter(file))
			{
				gson.toJson(array, writer);
			}
			return;
		}

		Map<String, int[]> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, int[]> e : keys.entrySet())
		{
			out.put(Integer.toString(e.getKey()), e.getValue());
		}
		try (Writer writer = new FileWriter(file))
		{
			gson.toJson(out, writer);
		}
	}
}
