/*
 * Tolerant loader for object definitions (config index 2, archive 6).
 *
 * This cache was repacked by an RSPS tool that left a handful of duplicate file
 * ids in the object archive's file table, which makes RuneLite's stock
 * ObjectManager throw "duplicate file ids" and abandon all 57k objects. The file
 * *contents* are laid out positionally, so we can split them exactly like
 * ArchiveFiles does and simply let a duplicate id resolve to the last entry,
 * recovering every object definition (and, crucially, their model ids).
 */
package net.runelite.cache.editor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ObjectLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.index.FileData;
import net.runelite.cache.io.InputStream;

public class ObjectDefManager
{
	private final Map<Integer, ObjectDefinition> objects = new HashMap<>();

	public void load(Store store) throws IOException
	{
		Index cfg = store.getIndex(IndexType.CONFIGS);
		Archive archive = cfg.getArchive(ConfigType.OBJECT.getId());
		if (archive == null)
		{
			return;
		}

		Storage storage = store.getStorage();
		byte[] data = archive.decompress(storage.loadArchive(archive));
		if (data == null)
		{
			return;
		}

		FileData[] fileData = archive.getFileData();
		byte[][] contents = splitFiles(data, fileData.length);

		ObjectLoader loader = new ObjectLoader();
		loader.configureForRevision(archive.getRevision());

		for (int i = 0; i < fileData.length; i++)
		{
			int id = fileData[i].getId();
			try
			{
				// Later duplicate wins, matching how the client would index them.
				objects.put(id, loader.load(id, contents[i]));
			}
			catch (RuntimeException ex)
			{
				// skip a malformed definition rather than losing the rest
			}
		}
	}

	public ObjectDefinition get(int id)
	{
		return objects.get(id);
	}

	/** Override/insert a definition (used to apply TOML object patches over the binary defs). */
	public void put(int id, ObjectDefinition def)
	{
		objects.put(id, def);
	}

	public java.util.Collection<ObjectDefinition> getObjects()
	{
		return objects.values();
	}

	public int size()
	{
		return objects.size();
	}

	/**
	 * Positional file split, identical to ArchiveFiles#loadContents but returning
	 * raw content per slot so duplicate ids don't abort the load.
	 */
	private static byte[][] splitFiles(byte[] data, int filesCount)
	{
		if (filesCount == 1)
		{
			return new byte[][]{data};
		}

		InputStream stream = new InputStream(data);
		stream.setOffset(stream.getLength() - 1);
		int chunks = stream.readUnsignedByte();

		stream.setOffset(stream.getLength() - 1 - chunks * filesCount * 4);
		int[][] chunkSizes = new int[filesCount][chunks];
		int[] filesSize = new int[filesCount];

		for (int chunk = 0; chunk < chunks; ++chunk)
		{
			int chunkSize = 0;
			for (int id = 0; id < filesCount; ++id)
			{
				int delta = stream.readInt();
				chunkSize += delta;
				chunkSizes[id][chunk] = chunkSize;
				filesSize[id] += chunkSize;
			}
		}

		byte[][] fileContents = new byte[filesCount][];
		int[] fileOffsets = new int[filesCount];
		for (int i = 0; i < filesCount; ++i)
		{
			fileContents[i] = new byte[filesSize[i]];
		}

		stream.setOffset(0);
		for (int chunk = 0; chunk < chunks; ++chunk)
		{
			for (int id = 0; id < filesCount; ++id)
			{
				int chunkSize = chunkSizes[id][chunk];
				stream.readBytes(fileContents[id], fileOffsets[id], chunkSize);
				fileOffsets[id] += chunkSize;
			}
		}

		return fileContents;
	}
}
