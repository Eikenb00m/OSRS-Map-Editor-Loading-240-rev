/*
 * Mutable, in-memory representation of a single 64x64 map square while it is
 * being edited. Terrain lives in a MapDefinition (local coords) and scenery in
 * a LocationsDefinition (local coords). Both are the exact structures the
 * encoders (MapSaver / LocationsSaver) expect, so editing is just mutation.
 */
package net.runelite.cache.editor;

import java.util.ArrayList;
import java.util.List;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;
import static net.runelite.cache.region.Region.Z;

public class RegionModel
{
	private final int regionId;
	private final int regionX;
	private final int regionY;

	private final MapDefinition map;
	private final LocationsDefinition locations;

	// Cache framing info needed to write the archives back.
	int mapCompression;
	int locCompression;
	int mapRevision;
	int locRevision;
	boolean hasLocationArchive;

	// Terrain encoding this region was read with (so we save it back the same way).
	MapCodec.Fmt mapFmt = MapCodec.Fmt.SHORT;

	// XTEA keys the location archive was read with (and will be written with).
	// null == the cache stores locations unencrypted (typical for RSPS caches).
	int[] locKeys;

	private boolean dirty;

	public RegionModel(int regionId, MapDefinition map, LocationsDefinition locations)
	{
		this.regionId = regionId;
		this.regionX = regionId >> 8;
		this.regionY = regionId & 0xFF;
		this.map = map;
		this.locations = locations;
		ensureTiles();
	}

	private void ensureTiles()
	{
		Tile[][][] tiles = map.getTiles();
		for (int z = 0; z < Z; z++)
		{
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					if (tiles[z][x][y] == null)
					{
						tiles[z][x][y] = new Tile();
					}
				}
			}
		}
	}

	public int getRegionId()
	{
		return regionId;
	}

	public int getRegionX()
	{
		return regionX;
	}

	public int getRegionY()
	{
		return regionY;
	}

	public int getBaseX()
	{
		return regionX << 6;
	}

	public int getBaseY()
	{
		return regionY << 6;
	}

	public MapDefinition getMap()
	{
		return map;
	}

	public LocationsDefinition getLocations()
	{
		return locations;
	}

	public Tile getTile(int z, int x, int y)
	{
		return map.getTiles()[z][x][y];
	}

	public boolean isDirty()
	{
		return dirty;
	}

	public void markDirty()
	{
		dirty = true;
	}

	public void clearDirty()
	{
		dirty = false;
	}

	// ---- terrain edits -------------------------------------------------

	public void setUnderlay(int z, int x, int y, int underlayId)
	{
		getTile(z, x, y).underlayId = (short) underlayId;
		markDirty();
	}

	public void setOverlay(int z, int x, int y, int overlayId, int path, int rotation)
	{
		Tile t = getTile(z, x, y);
		t.overlayId = (short) overlayId;
		t.overlayPath = (byte) path;
		t.overlayRotation = (byte) rotation;
		markDirty();
	}

	public void setHeight(int z, int x, int y, Integer height)
	{
		getTile(z, x, y).height = height;
		markDirty();
	}

	public void setSettings(int z, int x, int y, int settings)
	{
		getTile(z, x, y).settings = (byte) settings;
		markDirty();
	}

	// ---- location edits ------------------------------------------------

	public List<Location> locationsAt(int z, int x, int y)
	{
		List<Location> result = new ArrayList<>();
		for (Location loc : locations.getLocations())
		{
			Position p = loc.getPosition();
			if (p.getZ() == z && p.getX() == x && p.getY() == y)
			{
				result.add(loc);
			}
		}
		return result;
	}

	public void addLocation(int id, int type, int orientation, int z, int x, int y)
	{
		locations.getLocations().add(new Location(id, type, orientation, new Position(x, y, z)));
		markDirty();
	}

	public void removeLocation(Location loc)
	{
		locations.getLocations().remove(loc);
		markDirty();
	}
}
