/*
 * Format-detecting map terrain codec.
 *
 * OSRS map squares (index 5, "m{x}_{y}") encode each tile as a run of attribute
 * opcodes. Two encodings exist in the wild:
 *   - SHORT: the tile attribute and overlay id are 16-bit. This is what the Reason
 *     server's (repacked) cache uses; it matches the bundled MapLoader/MapSaver.
 *   - BYTE:  the attribute and overlay id are 8-bit. This is the standard upstream
 *     OSRS / RuneLite encoding (e.g. the "near reality" cache).
 *
 * Reading the wrong width walks off the end of the buffer, so {@link #detect} picks
 * the width that consumes the archive exactly. SHORT mode is byte-for-byte identical
 * to the previous MapLoader/MapSaver, so existing caches are unaffected.
 */
package net.runelite.cache.editor;

import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.io.InputStream;
import net.runelite.cache.io.OutputStream;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;
import static net.runelite.cache.region.Region.Z;

public final class MapCodec
{
	public enum Fmt { SHORT, BYTE }

	private MapCodec()
	{
	}

	/** Decode a region's terrain in the given format. Throws on malformed data. */
	public static MapDefinition decode(int regionX, int regionY, byte[] buf, Fmt fmt)
	{
		MapDefinition map = new MapDefinition();
		map.setRegionX(regionX);
		map.setRegionY(regionY);
		Tile[][][] tiles = map.getTiles();
		InputStream in = new InputStream(buf);
		boolean shortFmt = fmt == Fmt.SHORT;

		for (int z = 0; z < Z; z++)
		{
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					Tile tile = tiles[z][x][y] = new Tile();
					while (true)
					{
						int attribute = shortFmt ? in.readUnsignedShort() : in.readUnsignedByte();
						if (attribute == 0)
						{
							break;
						}
						else if (attribute == 1)
						{
							tile.height = in.readUnsignedByte();
							break;
						}
						else if (attribute <= 49)
						{
							tile.attrOpcode = attribute;
							tile.overlayId = (short) (shortFmt ? in.readShort() : in.readUnsignedByte());
							tile.overlayPath = (byte) ((attribute - 2) / 4);
							tile.overlayRotation = (byte) (attribute - 2 & 3);
						}
						else if (attribute <= 81)
						{
							tile.settings = (byte) (attribute - 49);
						}
						else
						{
							tile.underlayId = (short) (attribute - 81);
						}
					}
				}
			}
		}
		return map;
	}

	/** Encode terrain in the given format — the exact inverse of {@link #decode}. */
	public static byte[] encode(MapDefinition map, Fmt fmt)
	{
		OutputStream out = new OutputStream();
		Tile[][][] tiles = map.getTiles();
		boolean shortFmt = fmt == Fmt.SHORT;

		for (int z = 0; z < Z; z++)
		{
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						tile = new Tile();
					}
					if (tile.overlayId != 0)
					{
						int path = tile.overlayPath & 0xFF;
						int rot = tile.overlayRotation & 0x3;
						int attribute = 2 + (path * 4) + rot;
						write(out, shortFmt, attribute);
						write(out, shortFmt, tile.overlayId & (shortFmt ? 0xFFFF : 0xFF));
					}
					if (tile.settings != 0)
					{
						write(out, shortFmt, (tile.settings & 0xFF) + 49);
					}
					if (tile.underlayId != 0)
					{
						write(out, shortFmt, (tile.underlayId & 0xFFFF) + 81);
					}
					if (tile.height != null)
					{
						write(out, shortFmt, 1);
						out.writeByte(tile.height);
					}
					else
					{
						write(out, shortFmt, 0);
					}
				}
			}
		}
		return out.flip();
	}

	private static void write(OutputStream out, boolean shortFmt, int v)
	{
		if (shortFmt)
		{
			out.writeShort(v);
		}
		else
		{
			out.writeByte(v);
		}
	}

	/**
	 * Pick the encoding that decodes this buffer cleanly (consumes every byte with no
	 * under/overflow). Defaults to SHORT (the previous behaviour) when ambiguous.
	 */
	public static Fmt detect(byte[] buf)
	{
		if (consumesCleanly(buf, Fmt.SHORT))
		{
			return Fmt.SHORT;
		}
		if (consumesCleanly(buf, Fmt.BYTE))
		{
			return Fmt.BYTE;
		}
		return Fmt.SHORT;
	}

	/**
	 * Does this buffer look like region terrain? True only if it decodes to exactly 4x64x64 tiles
	 * AND consumes every byte, in either width — a locations file effectively never does both.
	 * Lets loose map files be classified by CONTENT, which is the only reliable way when the
	 * filenames come from a foreign cache (RSPSi names its dumps after archive ids).
	 */
	public static boolean isTerrain(byte[] buf)
	{
		return consumesCleanly(buf, Fmt.SHORT) || consumesCleanly(buf, Fmt.BYTE);
	}

	private static boolean consumesCleanly(byte[] buf, Fmt fmt)
	{
		try
		{
			InputStream in = new InputStream(buf);
			boolean shortFmt = fmt == Fmt.SHORT;
			for (int i = 0; i < Z * X * Y; i++)
			{
				while (true)
				{
					int attribute = shortFmt ? in.readUnsignedShort() : in.readUnsignedByte();
					if (attribute == 0)
					{
						break;
					}
					else if (attribute == 1)
					{
						in.readUnsignedByte();
						break;
					}
					else if (attribute <= 49)
					{
						if (shortFmt)
						{
							in.readShort();
						}
						else
						{
							in.readUnsignedByte();
						}
					}
					// settings / underlay carry no extra bytes
				}
			}
			return in.getOffset() == in.getLength(); // every byte accounted for
		}
		catch (RuntimeException ex)
		{
			return false; // ran off the end — wrong width
		}
	}
}
