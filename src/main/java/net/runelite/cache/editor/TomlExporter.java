/*
 * Exports a region's terrain + objects to the TOML region format used by the
 * Reason server's TOML cache (data/cache/toml/0_jagex/region/{x}_{y}/region.toml).
 *
 * Each tile is a [x_y_plane] section. Fields mirror the raw OSRS tile opcodes:
 *   height        = tile height byte (opcode 1)
 *   attr          = [settings opcode]  (settings value + 49, range 50..81)
 *   attr_underlay = [underlay opcode]  (underlay id + 81)
 *   attr_overlay  = overlay opcode     (2 + path*4 + rotation)
 *   overlay       = overlay id
 *   objects       = [{ id, attributes }]  where attributes = (type << 2) | rotation
 */
package net.runelite.cache.editor;

import java.util.ArrayList;
import java.util.List;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.region.Location;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;
import static net.runelite.cache.region.Region.Z;

public final class TomlExporter
{
	private TomlExporter()
	{
	}

	public static String export(RegionModel model)
	{
		MapDefinition map = model.getMap();
		Tile[][][] tiles = map.getTiles();

		// Index objects by (x,y,plane) so each tile can list the ones on it.
		List<Location>[][][] locs = newLocGrid();
		for (Location l : model.getLocations().getLocations())
		{
			var p = l.getPosition();
			if (p.getZ() >= 0 && p.getZ() < Z && p.getX() >= 0 && p.getX() < X && p.getY() >= 0 && p.getY() < Y)
			{
				locs[p.getX()][p.getY()][p.getZ()].add(l);
			}
		}

		// Section order matches the server's dump: plane-outer, then x, then y.
		StringBuilder sb = new StringBuilder();
		for (int z = 0; z < Z; z++)
		{
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					Tile t = tiles[z][x][y];
					List<Location> here = locs[x][y][z];
					boolean hasTerrain = t != null && (t.height != null || t.settings != 0
						|| t.overlayId != 0 || t.underlayId != 0);
					if (!hasTerrain && here.isEmpty())
					{
						continue; // skip fully-empty tiles
					}

					sb.append('[').append(x).append('_').append(y).append('_').append(z).append("]\n");
					if (t != null && t.height != null)
					{
						sb.append("height = ").append((int) t.height).append('\n');
					}
					if (t != null && t.settings != 0)
					{
						sb.append("attr = [").append((t.settings & 0xFF) + 49).append("]\n");
					}
					if (t != null && t.underlayId != 0)
					{
						sb.append("attr_underlay = [").append((t.underlayId & 0xFFFF) + 81).append("]\n");
					}
					if (t != null && t.overlayId != 0)
					{
						int attr = 2 + (t.overlayPath & 0xFF) * 4 + (t.overlayRotation & 3);
						sb.append("attr_overlay = ").append(attr).append('\n');
						sb.append("overlay = ").append(t.overlayId & 0xFFFF).append('\n');
					}
					if (!here.isEmpty())
					{
						sb.append("objects = [");
						for (int i = 0; i < here.size(); i++)
						{
							Location l = here.get(i);
							int attributes = (l.getType() << 2) | (l.getOrientation() & 3);
							if (i > 0)
							{
								sb.append(", ");
							}
							sb.append("{ id = ").append(l.getId())
								.append(", attributes = ").append(attributes).append(" }");
						}
						sb.append("]\n");
					}
					sb.append('\n');
				}
			}
		}
		// Drop the trailing blank line so output matches the server's dump exactly.
		if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n')
		{
			sb.deleteCharAt(sb.length() - 1);
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static List<Location>[][][] newLocGrid()
	{
		List<Location>[][][] g = new List[X][Y][Z];
		for (int x = 0; x < X; x++)
		{
			for (int y = 0; y < Y; y++)
			{
				for (int z = 0; z < Z; z++)
				{
					g[x][y][z] = new ArrayList<>();
				}
			}
		}
		return g;
	}
}
