/*
 * Location (scenery / walls / objects) encoder for the map editor.
 *
 * This is the exact inverse of net.runelite.cache.definitions.loaders.LocationsLoader.
 * The output is the raw bytes that get gzipped, XTEA-encrypted and stored in the
 * "l{x}_{y}" archive of index 5.
 *
 * Wire format:
 *   for each object id (ascending):
 *       idOffset      (smart-int, delta from previous id, prev starts at -1)
 *       for each placement of that id (ascending packed position):
 *           positionOffset (smart-short, delta from previous packed pos + 1, prev starts at 0)
 *           attributes     (byte:  type << 2 | orientation)
 *       0 terminator (smart-short)
 *   0 terminator (smart-short)
 */
package net.runelite.cache.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.io.OutputStream;
import net.runelite.cache.region.Location;

public class LocationsSaver
{
	public byte[] save(LocationsDefinition locs)
	{
		OutputStream out = new OutputStream();

		// Work on a sorted copy: id ascending, then packed local position ascending.
		List<Location> sorted = new ArrayList<>(locs.getLocations());
		sorted.sort(Comparator
			.comparingInt(Location::getId)
			.thenComparingInt(LocationsSaver::packedPosition));

		int lastId = -1;
		int index = 0;
		while (index < sorted.size())
		{
			int id = sorted.get(index).getId();

			writeSmartIntShortCompat(out, id - lastId);
			lastId = id;

			int lastPos = 0;
			while (index < sorted.size() && sorted.get(index).getId() == id)
			{
				Location loc = sorted.get(index);
				int pos = packedPosition(loc);

				out.writeShortSmart(pos - lastPos + 1);
				lastPos = pos;

				int attributes = ((loc.getType() & 0x3F) << 2) | (loc.getOrientation() & 0x3);
				out.writeByte(attributes);

				index++;
			}

			// End of this id's placements.
			out.writeShortSmart(0);
		}

		// End of all ids (idOffset of 0 terminates the outer loop).
		writeSmartIntShortCompat(out, 0);

		return out.flip();
	}

	private static int packedPosition(Location loc)
	{
		int x = loc.getPosition().getX() & 0x3F;
		int y = loc.getPosition().getY() & 0x3F;
		int z = loc.getPosition().getZ() & 0x3;
		return (z << 12) | (x << 6) | y;
	}

	/**
	 * Inverse of InputStream#readUnsignedIntSmartShortCompat: emit repeated 32767
	 * chunks followed by the remainder. Writing 0 emits a single terminating byte.
	 */
	private static void writeSmartIntShortCompat(OutputStream out, int value)
	{
		while (value >= 32767)
		{
			out.writeShortSmart(32767);
			value -= 32767;
		}
		out.writeShortSmart(value);
	}
}
