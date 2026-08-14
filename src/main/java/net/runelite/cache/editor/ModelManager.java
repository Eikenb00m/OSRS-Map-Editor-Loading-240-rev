/*
 * Loads 3D model geometry from index 7, with a small cache. Each model is a
 * single-file archive whose decompressed bytes are the model definition.
 */
package net.runelite.cache.editor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

public class ModelManager
{
	private final Store store;
	private final Index index;
	private final Storage storage;
	private final ModelLoader loader = new ModelLoader();
	private final Map<Integer, ModelDefinition> cache = new HashMap<>();

	public ModelManager(Store store)
	{
		this.store = store;
		this.index = store.getIndex(IndexType.MODELS);
		this.storage = store.getStorage();
	}

	public ModelDefinition get(int modelId)
	{
		if (cache.containsKey(modelId))
		{
			return cache.get(modelId);
		}
		ModelDefinition def = null;
		try
		{
			Archive a = index.getArchive(modelId);
			if (a != null)
			{
				byte[] data = a.decompress(storage.loadArchive(a));
				if (data != null)
				{
					def = loader.load(modelId, data);
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			def = null;
		}
		cache.put(modelId, def);
		return def;
	}
}
