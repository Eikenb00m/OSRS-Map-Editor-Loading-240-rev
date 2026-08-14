/*
 * Map terrain encoder for the map editor.
 *
 * This is the exact inverse of net.runelite.cache.definitions.loaders.MapLoader.
 * It turns a (possibly edited) MapDefinition back into the raw bytes that get
 * gzipped and stored in the "m{x}_{y}" archive of index 5.
 */
package net.runelite.cache.editor;

import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.io.OutputStream;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;
import static net.runelite.cache.region.Region.Z;

public class MapSaver
{
	public byte[] save(MapDefinition map)
	{
		OutputStream out = new OutputStream();
		Tile[][][] tiles = map.getTiles();

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

					// Overlay: attribute 2..49 carries path + rotation, then a short overlay id.
					if (tile.overlayId != 0)
					{
						int path = tile.overlayPath & 0xFF;
						int rot = tile.overlayRotation & 0x3;
						int attribute = 2 + (path * 4) + rot;
						out.writeShort(attribute);
						out.writeShort(tile.overlayId);
					}

					// Settings (collision / bridge flags): attribute 50..81.
					if (tile.settings != 0)
					{
						out.writeShort((tile.settings & 0xFF) + 49);
					}

					// Underlay (ground texture): attribute > 81.
					if (tile.underlayId != 0)
					{
						out.writeShort((tile.underlayId & 0xFFFF) + 81);
					}

					// Height (opcode 1) doubles as the tile terminator; opcode 0 otherwise.
					if (tile.height != null)
					{
						out.writeShort(1);
						out.writeByte(tile.height);
					}
					else
					{
						out.writeShort(0);
					}
				}
			}
		}

		return out.flip();
	}
}
