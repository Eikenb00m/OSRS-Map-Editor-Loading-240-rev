/*
 * Tolerant NPC-definition loader (config index 2, NPC archive). Same approach as
 * ObjectDefManager: split the archive contents positionally so any duplicate file
 * ids left by an RSPS repack don't abort the load. Gives us NPC model ids + size
 * so spawns can be drawn as their real models.
 */
package net.runelite.cache.editor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.index.FileData;
import net.runelite.cache.io.InputStream;

public class NpcDefManager
{
	private final Map<Integer, NpcDefinition> npcs = new HashMap<>();

	public void load(Store store) throws IOException
	{
		Index cfg = store.getIndex(IndexType.CONFIGS);
		Archive archive = cfg.getArchive(ConfigType.NPC.getId());
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

		NpcLoader loader = new NpcLoader();
		loader.configureForRevision(archive.getRevision());

		for (int i = 0; i < fileData.length; i++)
		{
			int id = fileData[i].getId();
			try
			{
				npcs.put(id, loader.load(id, contents[i]));
			}
			catch (RuntimeException ex)
			{
				// skip a malformed definition
			}
		}
	}

	public NpcDefinition get(int id)
	{
		return npcs.get(id);
	}

	public java.util.Collection<NpcDefinition> getNpcs()
	{
		return npcs.values();
	}

	public int size()
	{
		return npcs.size();
	}

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
