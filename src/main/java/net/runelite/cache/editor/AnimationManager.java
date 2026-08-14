/*
 * Loads animation sequences (config 12), skeletons/framemaps (index 1) and frames
 * (index 0), and poses a model to a given frame — replicating the client's frame
 * application (FramemapDefinition types/frameMaps + ModelDefinition.animate).
 *
 * Sequences are split tolerantly (same duplicate-file-id workaround as the other
 * config loaders). Framemaps and frame groups are loaded lazily and cached.
 */
package net.runelite.cache.editor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FrameLoader;
import net.runelite.cache.definitions.loaders.FramemapLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.index.FileData;
import net.runelite.cache.io.InputStream;

public class AnimationManager
{
	private final Store store;
	private final Storage storage;
	private final Index frameIndex;     // index 0
	private final Index framemapIndex;  // index 1

	private final Map<Integer, SequenceDefinition> sequences = new HashMap<>();
	private final Map<Integer, FramemapDefinition> framemaps = new HashMap<>();
	private final Map<Integer, Map<Integer, byte[]>> frameGroups = new HashMap<>();
	private final Map<Integer, FrameDefinition> frames = new HashMap<>();

	public AnimationManager(Store store)
	{
		this.store = store;
		this.storage = store.getStorage();
		this.frameIndex = store.getIndex(IndexType.ANIMATIONS);
		this.framemapIndex = store.getIndex(IndexType.SKELETONS);
	}

	public void load() throws IOException
	{
		Index cfg = store.getIndex(IndexType.CONFIGS);
		Archive archive = cfg.getArchive(ConfigType.SEQUENCE.getId());
		if (archive == null)
		{
			return;
		}
		byte[] data = archive.decompress(storage.loadArchive(archive));
		FileData[] fd = archive.getFileData();
		byte[][] contents = splitPositional(data, fd.length);
		SequenceLoader loader = new SequenceLoader();
		loader.configureForRevision(archive.getRevision());
		for (int i = 0; i < fd.length; i++)
		{
			try
			{
				sequences.put(fd[i].getId(), loader.load(fd[i].getId(), contents[i]));
			}
			catch (RuntimeException ex)
			{
				// skip malformed
			}
		}
	}

	public int sequenceCount()
	{
		return sequences.size();
	}

	public SequenceDefinition getSequence(int id)
	{
		return sequences.get(id);
	}

	/**
	 * Returns a posed copy of the model for the given animation at the given tick,
	 * or the original model if it can't be animated.
	 */
	public ModelDefinition pose(ModelDefinition base, int animId, int tick)
	{
		if (base == null || base.packedVertexGroups == null)
		{
			return base; // static model, nothing to pose
		}
		SequenceDefinition seq = sequences.get(animId);
		if (seq == null || seq.frameIDs == null || seq.frameIDs.length == 0)
		{
			return base;
		}
		int frameIdx = frameForTick(seq, tick);
		FrameDefinition frame = getFrame(seq.frameIDs[frameIdx]);
		if (frame == null || frame.framemap == null || frame.indexFrameIds == null)
		{
			return base;
		}

		ModelDefinition clone = cloneForAnim(base);
		clone.computeAnimationTables();
		FramemapDefinition fm = frame.framemap;
		for (int i = 0; i < frame.translatorCount; i++)
		{
			int group = frame.indexFrameIds[i];
			if (group < 0 || group >= fm.types.length)
			{
				continue;
			}
			clone.animate(fm.types[group], fm.frameMaps[group],
				frame.translator_x[i], frame.translator_y[i], frame.translator_z[i]);
		}
		return clone;
	}

	private static int frameForTick(SequenceDefinition seq, int tick)
	{
		int n = seq.frameIDs.length;
		int[] len = seq.frameLengths;
		if (len == null || len.length != n)
		{
			return ((tick % n) + n) % n;
		}
		int total = 0;
		for (int l : len)
		{
			total += Math.max(1, l);
		}
		int t = ((tick % total) + total) % total;
		int acc = 0;
		for (int i = 0; i < n; i++)
		{
			acc += Math.max(1, len[i]);
			if (t < acc)
			{
				return i;
			}
		}
		return n - 1;
	}

	private FrameDefinition getFrame(int frameId)
	{
		FrameDefinition cached = frames.get(frameId);
		if (cached != null || frames.containsKey(frameId))
		{
			return cached;
		}
		FrameDefinition def = null;
		try
		{
			int group = frameId >>> 16;
			int file = frameId & 0xFFFF;
			Map<Integer, byte[]> groupFiles = frameGroups.get(group);
			if (groupFiles == null && !frameGroups.containsKey(group))
			{
				groupFiles = loadGroup(group);
				frameGroups.put(group, groupFiles);
			}
			if (groupFiles != null)
			{
				byte[] fb = groupFiles.get(file);
				if (fb != null && fb.length >= 2)
				{
					int framemapId = ((fb[0] & 0xFF) << 8) | (fb[1] & 0xFF);
					FramemapDefinition fm = getFramemap(framemapId);
					if (fm != null)
					{
						def = new FrameLoader().load(fm, file, fb);
					}
				}
			}
		}
		catch (Exception ex)
		{
			def = null;
		}
		frames.put(frameId, def);
		return def;
	}

	private Map<Integer, byte[]> loadGroup(int group) throws IOException
	{
		Archive a = frameIndex.getArchive(group);
		if (a == null)
		{
			return null;
		}
		byte[] data = a.decompress(storage.loadArchive(a));
		FileData[] fd = a.getFileData();
		byte[][] pos = splitPositional(data, fd.length);
		Map<Integer, byte[]> map = new HashMap<>();
		for (int i = 0; i < fd.length; i++)
		{
			map.put(fd[i].getId(), pos[i]);
		}
		return map;
	}

	private FramemapDefinition getFramemap(int id)
	{
		FramemapDefinition fm = framemaps.get(id);
		if (fm != null || framemaps.containsKey(id))
		{
			return fm;
		}
		try
		{
			Archive a = framemapIndex.getArchive(id);
			if (a != null)
			{
				byte[] data = a.decompress(storage.loadArchive(a));
				fm = new FramemapLoader().load(id, data);
			}
		}
		catch (Exception ex)
		{
			fm = null;
		}
		framemaps.put(id, fm);
		return fm;
	}

	private static ModelDefinition cloneForAnim(ModelDefinition base)
	{
		ModelDefinition c = new ModelDefinition();
		c.vertexCount = base.vertexCount;
		c.vertexX = base.vertexX.clone();
		c.vertexY = base.vertexY.clone();
		c.vertexZ = base.vertexZ.clone();
		c.faceCount = base.faceCount;
		c.faceIndices1 = base.faceIndices1;
		c.faceIndices2 = base.faceIndices2;
		c.faceIndices3 = base.faceIndices3;
		c.faceColors = base.faceColors;
		c.faceRenderTypes = base.faceRenderTypes;
		c.faceTextures = base.faceTextures;
		c.faceTextureUCoordinates = base.faceTextureUCoordinates;
		c.faceTextureVCoordinates = base.faceTextureVCoordinates;
		c.packedVertexGroups = base.packedVertexGroups;
		return c;
	}

	private static byte[][] splitPositional(byte[] data, int filesCount)
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
