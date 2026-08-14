/*
 * Turns a RegionModel + plane into a Renderer3D.Scene: a terrain mesh built from
 * tile heights and underlay/overlay colours (textured overlays use their real
 * texture colour), every object placed as its real 3D model (index 7) with
 * per-face textures where present, and server spawns drawn as their NPC / object
 * models (falling back to a coloured pole when a model isn't available).
 */
package net.runelite.cache.editor;

import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.models.JagexColor;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;

public class SceneBuilder
{
	private static final int TILE = 128;
	private static final double LX = -0.5, LY = -1.0, LZ = -0.4;
	private static final double LLEN = Math.sqrt(LX * LX + LY * LY + LZ * LZ);

	private final MapEditorService service;
	private Location highlight; // the currently-selected object, drawn bright
	private MapRenderer.Options layers; // shared Hide/Show Elements flags
	// Per-model averaged vertex normals (model space), cached so smooth model lighting
	// doesn't recompute them for every placement of the same model.
	private final java.util.Map<ModelDefinition, float[][]> normalCache = new java.util.WeakHashMap<>();
	// When false, terrain/models render flat colours instead of textures — matches a client
	// that has ground textures turned off (many RSPS render terrain flat).
	private boolean renderTextures = true;

	// Bit packed into the placement orientation to mark type-11 diagonal scenery (+45° rotation).
	// Safe because a real orientation is only 0-3; the vertex transforms mask it with (orientation & 3).
	private static final int DIAGONAL = 4;

	// Bit packed into the placement orientation to mark an object whose def has isRotated=true
	// (opcode 62). The client mirrors such models across Z (negate vertexZ + reverse triangle
	// winding) BEFORE applying the placement rotation — see ObjectDefinition.getModelData /
	// ModelData.method2817 in the deob client. Reproduced here so mirrored roof pieces
	// (e.g. 5649/5650/5653) sit correctly instead of straight/offset.
	private static final int MIRROR = 8;

	// DISPLAY-ONLY per-object-id rotation override (extra 90° steps), so the user can make a
	// mis-rendered custom object look right in the editor WITHOUT changing any map data. The
	// game reads the real orientation, so it's never affected. Keyed by object id.
	private final java.util.Map<Integer, Integer> displayRot = new java.util.HashMap<>();

	public void setDisplayRot(int objectId, int steps)
	{
		steps &= 3;
		if (steps == 0) { displayRot.remove(objectId); }
		else { displayRot.put(objectId, steps); }
	}

	public int getDisplayRot(int objectId)
	{
		Integer v = displayRot.get(objectId);
		return v == null ? 0 : v;
	}

	public java.util.Map<Integer, Integer> displayRotMap()
	{
		return displayRot;
	}

	// Neighbour-edge preview: cached full scenes of the 8 surrounding regions (N,S,E,W +
	// the 4 diagonals for the corners), rebuilt only when the region/plane/options change.
	private static final int STRIP_TILES = 5;
	private final Renderer3D.Scene[] neighborScenes = new Renderer3D.Scene[8];
	private String neighborSig;

	public void invalidateNeighbors()
	{
		neighborSig = null;
	}

	/**
	 * Appends dimmed neighbor regions around {@code model} in the 3D scene. {@code stripTiles}
	 * controls how many tiles to show: use {@value #STRIP_TILES} for a thin reference edge,
	 * or {@link Region#X} (64) for a full 3×3 grid of regions. Cheap after the first call
	 * (neighbour scenes are cached and reused regardless of strip size).
	 */
	public void appendNeighborStrips(Renderer3D.Scene scene, RegionModel model, int plane,
		boolean renderObjects, int stripTiles)
	{
		int rx = model.getRegionX(), ry = model.getRegionY();
		String sig = model.getRegionId() + "/" + plane + "/" + renderObjects + "/" + renderTextures
			+ "/" + (layers != null && layers.showOverlay) + "/" + (layers != null && layers.allPlanes)
			+ "/" + (layers != null && layers.ghostLowerPlanes);
		if (!sig.equals(neighborSig))
		{
			neighborSig = sig;
			int[] nb = {
				(rx << 8) | (ry + 1), (rx << 8) | (ry - 1), ((rx + 1) << 8) | ry, ((rx - 1) << 8) | ry, // N,S,E,W
				((rx + 1) << 8) | (ry + 1), ((rx - 1) << 8) | (ry + 1),                                 // NE,NW
				((rx + 1) << 8) | (ry - 1), ((rx - 1) << 8) | (ry - 1),                                 // SE,SW
			};
			for (int i = 0; i < neighborScenes.length; i++)
			{
				neighborScenes[i] = null;
				try
				{
					RegionModel nm = service.loadRegion(nb[i]);
					if (nm != null)
					{
						neighborScenes[i] = build(nm, plane, renderObjects, null, -1);
					}
				}
				catch (Throwable t)
				{
					neighborScenes[i] = null;
				}
			}
		}
		float full = 64f * TILE, strip = Math.min(stripTiles, 64) * TILE, dim = 0.5f, INF = 1e9f;
		// A region only renders 63 of its 64 tile rows (the last needs a neighbour's heights),
		// so join the neighbour to the rendered edge at (full - TILE) — no gap.
		float off = full - TILE;
		// Sides.
		if (neighborScenes[0] != null) { scene.appendClipped(neighborScenes[0], 0, off, -INF, INF, 0, strip, dim); }               // N: neighbour south edge
		if (neighborScenes[1] != null) { scene.appendClipped(neighborScenes[1], 0, -off, -INF, INF, full - strip, full, dim); }    // S: north edge
		if (neighborScenes[2] != null) { scene.appendClipped(neighborScenes[2], off, 0, 0, strip, -INF, INF, dim); }               // E: west edge
		if (neighborScenes[3] != null) { scene.appendClipped(neighborScenes[3], -off, 0, full - strip, full, -INF, INF, dim); }    // W: east edge
		// Corners (diagonal neighbours) — fill the empty squares where two sides meet.
		if (neighborScenes[4] != null) { scene.appendClipped(neighborScenes[4], off, off, 0, strip, 0, strip, dim); }              // NE: neighbour SW corner
		if (neighborScenes[5] != null) { scene.appendClipped(neighborScenes[5], -off, off, full - strip, full, 0, strip, dim); }   // NW: SE corner
		if (neighborScenes[6] != null) { scene.appendClipped(neighborScenes[6], off, -off, 0, strip, full - strip, full, dim); }   // SE: NW corner
		if (neighborScenes[7] != null) { scene.appendClipped(neighborScenes[7], -off, -off, full - strip, full, full - strip, full, dim); } // SW: NE corner
	}

	public void setTextures(boolean on)
	{
		this.renderTextures = on;
	}

	public boolean isTextures()
	{
		return renderTextures;
	}

	public SceneBuilder(MapEditorService service)
	{
		this.service = service;
	}

	public void setHighlight(Location loc)
	{
		this.highlight = loc;
	}

	public void setLayerOptions(MapRenderer.Options options)
	{
		this.layers = options;
	}

	public Renderer3D.Scene build(RegionModel model, int plane, boolean renderObjects)
	{
		return build(model, plane, renderObjects, null, -1);
	}

	public Renderer3D.Scene build(RegionModel model, int plane, boolean renderObjects,
		java.util.List<SpawnLoader.Spawn> spawns)
	{
		return build(model, plane, renderObjects, spawns, -1);
	}

	/** animTick >= 0 poses animated models to that tick; -1 keeps them static. */
	public Renderer3D.Scene build(RegionModel model, int plane, boolean renderObjects,
		java.util.List<SpawnLoader.Spawn> spawns, int animTick)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();

		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());

		boolean all = layers != null && layers.allPlanes;
		// Onion-skin: also build the planes below the current one so you can see the
		// lower-floor layout in 3D; they're dimmed so the edited plane stands out.
		boolean ghost = layers != null && layers.ghostLowerPlanes && !all && plane > 0;
		int lo = (all || ghost) ? 0 : plane;
		int hi = all ? Math.max(plane, 3) : plane;
		for (int pz = lo; pz <= hi; pz++)
		{
			int start = scene.size();
			buildTerrain(scene, model, region, pz);
			if (renderObjects && service.getObjectDefs() != null && service.getModels() != null)
			{
				buildObjects(scene, model, region, pz, animTick);
			}
			if (spawns != null)
			{
				buildSpawns(scene, model, region, pz, spawns, animTick);
			}
			if (layers != null && layers.showServerSpawns
				&& service.getObjectDefs() != null && service.getModels() != null)
			{
				buildServerSpawns(scene, model, region, pz, animTick);
			}
			if (ghost && pz < plane)
			{
				scene.dimFrom(start, 0.4f);
			}
		}
		return scene;
	}

	/**
	 * Builds the STATIC part of the scene for animation: terrain + spawns + every object EXCEPT the
	 * animated ones. Built once when animation starts; the animation loop then only re-appends the
	 * animated object models each tick (see {@link #appendAnimatedObjects}) instead of rebuilding
	 * the whole scene — which is what made animation laggy.
	 */
	public Renderer3D.Scene buildStatic(RegionModel model, int plane, boolean renderObjects,
		java.util.List<SpawnLoader.Spawn> spawns)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());
		boolean all = layers != null && layers.allPlanes;
		boolean ghost = layers != null && layers.ghostLowerPlanes && !all && plane > 0;
		int lo = (all || ghost) ? 0 : plane;
		int hi = all ? Math.max(plane, 3) : plane;
		for (int pz = lo; pz <= hi; pz++)
		{
			int start = scene.size();
			buildTerrain(scene, model, region, pz);
			if (renderObjects && service.getObjectDefs() != null && service.getModels() != null)
			{
				buildObjects(scene, model, region, pz, -1, FILTER_STATIC);
			}
			// Spawns (NPCs especially) are re-posed each tick in appendAnimatedObjects, so they are
			// NOT built into the static base — otherwise they'd be frozen at frame 0.
			if (layers != null && layers.showServerSpawns
				&& service.getObjectDefs() != null && service.getModels() != null)
			{
				buildServerSpawns(scene, model, region, pz, -1);
			}
			if (ghost && pz < plane)
			{
				scene.dimFrom(start, 0.4f);
			}
		}
		return scene;
	}

	/**
	 * Appends the per-tick DYNAMIC part: animated object models AND all spawns (NPCs/object spawns),
	 * posed for {@code animTick}. Spawns live here so NPCs actually animate instead of freezing.
	 */
	public void appendAnimatedObjects(Renderer3D.Scene scene, RegionModel model, int plane, int animTick,
		java.util.List<SpawnLoader.Spawn> spawns)
	{
		boolean haveObjs = service.getObjectDefs() != null && service.getModels() != null;
		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());
		boolean all = layers != null && layers.allPlanes;
		boolean ghost = layers != null && layers.ghostLowerPlanes && !all && plane > 0;
		int lo = (all || ghost) ? 0 : plane;
		int hi = all ? Math.max(plane, 3) : plane;
		for (int pz = lo; pz <= hi; pz++)
		{
			int start = scene.size();
			if (haveObjs)
			{
				buildObjects(scene, model, region, pz, animTick, FILTER_ANIMATED);
			}
			if (spawns != null)
			{
				buildSpawns(scene, model, region, pz, spawns, animTick);
			}
			if (ghost && pz < plane)
			{
				scene.dimFrom(start, 0.4f);
			}
		}
	}

	/**
	 * A terrain-only scene where every tile is flat-shaded with a unique colour
	 * encoding its (x,y). Rendering it and reading a pixel back is how a 3D click
	 * is mapped to a tile for editing. All tiles (even void) are included.
	 */
	public Renderer3D.Scene buildPickScene(RegionModel model, int plane)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());

		for (int x = 0; x < X - 1; x++)
		{
			for (int y = 0; y < Y - 1; y++)
			{
				int h00 = region.getTileHeight(plane, x, y);
				int h10 = region.getTileHeight(plane, x + 1, y);
				int h01 = region.getTileHeight(plane, x, y + 1);
				int h11 = region.getTileHeight(plane, x + 1, y + 1);

				int color = (y * 64 + x) + 1; // +1 so 0 == background/miss
				float x0 = x * TILE, x1 = (x + 1) * TILE;
				float z0 = y * TILE, z1 = (y + 1) * TILE;
				scene.add(x0, h00, z0, x1, h11, z1, x0, h01, z1, color);
				scene.add(x0, h00, z0, x1, h10, z0, x1, h11, z1, color);
			}
		}
		return scene;
	}

	/**
	 * A single object's model centred at the origin, for the palette preview.
	 * out[0] = bounding radius, out[1] = vertical centre (for the camera).
	 */
	public Renderer3D.Scene buildModelPreview(int objId, double[] out)
	{
		return buildModelPreview(objId, 0, out);
	}

	/** Preview at a specific placement rotation, so you can see the facing. */
	public Renderer3D.Scene buildModelPreview(int objId, int orientation, double[] out)
	{
		return buildModelPreview(objId, orientation, 10, out);
	}

	/** Preview using a specific placement type (so a wall corner shows its corner model, etc.). */
	public Renderer3D.Scene buildModelPreview(int objId, int orientation, int type, double[] out)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		out[0] = 400;
		out[1] = 0;
		ObjectDefinition def = service.getObjectDefs() != null ? service.getObjectDefs().get(objId) : null;
		if (def == null || service.getModels() == null)
		{
			return scene;
		}
		int[] types = def.getObjectTypes();
		int[] emitIds;
		if (types == null)
		{
			emitIds = def.getObjectModels(); // multi-part: preview all models together
		}
		else
		{
			int mid = pickModel(def, type);
			if (mid < 0) { return scene; }
			emitIds = new int[]{mid};
		}
		double sx = def.getModelSizeX() / 128.0, sy = def.getModelSizeHeight() / 128.0, sz = def.getModelSizeY() / 128.0;

		double r = 1, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		boolean any = false;
		for (int mid : emitIds)
		{
			ModelDefinition m = service.getModels().get(mid);
			if (m == null || m.vertexCount == 0)
			{
				continue;
			}
			for (int i = 0; i < m.vertexCount; i++)
			{
				double vx = m.vertexX[i] * sx, vy = m.vertexY[i] * sy, vz = m.vertexZ[i] * sz;
				r = Math.max(r, Math.sqrt(vx * vx + vy * vy + vz * vz));
				minY = Math.min(minY, vy);
				maxY = Math.max(maxY, vy);
			}
			int previewOrient = (type == 11 ? (orientation & 3) | DIAGONAL : orientation & 3);
			if (def.isRotated()) { previewOrient |= MIRROR; }
			emitModel(scene, m, 0, 0, 0, previewOrient, sx, sy, sz, -1,
				def.getRecolorToFind(), def.getRecolorToReplace(), def.getRetextureToFind(), def.getTextureToReplace(),
				null, 0);
			any = true;
		}
		if (any)
		{
			out[0] = r;
			out[1] = (minY + maxY) / 2.0;
		}
		return scene;
	}

	/** An NPC's combined model(s) centred at the origin, for the NPC viewer. */
	public Renderer3D.Scene buildNpcPreview(int npcId, double[] out)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		out[0] = 400;
		out[1] = 0;
		NpcDefinition def = service.getNpc(npcId);
		if (def == null || def.models == null || service.getModels() == null)
		{
			return scene;
		}
		double r = 1, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		boolean any = false;
		for (int mid : def.models)
		{
			ModelDefinition m = service.getModels().get(mid);
			if (m == null || m.vertexCount == 0)
			{
				continue;
			}
			for (int i = 0; i < m.vertexCount; i++)
			{
				double vx = m.vertexX[i], vy = m.vertexY[i], vz = m.vertexZ[i];
				r = Math.max(r, Math.sqrt(vx * vx + vy * vy + vz * vz));
				minY = Math.min(minY, vy);
				maxY = Math.max(maxY, vy);
			}
			emitModel(scene, m, 0, 0, 0, 0, 1, 1, 1, -1);
			any = true;
		}
		if (any)
		{
			out[0] = r;
			out[1] = (minY + maxY) / 2.0;
		}
		return scene;
	}

	/** A raw model by id centred at the origin, for the model viewer. */
	public Renderer3D.Scene buildModelPreviewById(int modelId, double[] out)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		out[0] = 400;
		out[1] = 0;
		ModelDefinition m = service.getModels() != null ? service.getModels().get(modelId) : null;
		if (m == null || m.vertexCount == 0)
		{
			return scene;
		}
		double r = 1, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int i = 0; i < m.vertexCount; i++)
		{
			double vx = m.vertexX[i], vy = m.vertexY[i], vz = m.vertexZ[i];
			r = Math.max(r, Math.sqrt(vx * vx + vy * vy + vz * vz));
			minY = Math.min(minY, vy);
			maxY = Math.max(maxY, vy);
		}
		out[0] = r;
		out[1] = (minY + maxY) / 2.0;
		emitModel(scene, m, 0, 0, 0, 0, 1, 1, 1, -1);
		return scene;
	}

	public Renderer3D.Scene buildObjectsOnly(RegionModel model, int plane)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());
		if (service.getObjectDefs() != null && service.getModels() != null)
		{
			buildObjects(scene, model, region, plane, -1);
		}
		return scene;
	}

	// ---- terrain -------------------------------------------------------

	private void buildTerrain(Renderer3D.Scene scene, RegionModel model, Region region, int z)
	{
		boolean showOverlay = layers == null || layers.showOverlay;

		// Precompute a smooth per-vertex light map (from the heightmap) and a blended
		// underlay-colour grid (neighbour average), so terrain reads like the game does:
		// smoothly lit slopes/steps and soft ground-colour gradients instead of flat facets.
		float[][] light = computeVertexLight(region, z);
		int[][] uBlend = computeBlendedUnderlay(model, z);

		for (int x = 0; x < X - 1; x++)
		{
			for (int y = 0; y < Y - 1; y++)
			{
				int h00 = region.getTileHeight(z, x, y);
				int h10 = region.getTileHeight(z, x + 1, y);
				int h01 = region.getTileHeight(z, x, y + 1);
				int h11 = region.getTileHeight(z, x + 1, y + 1);

				var tile = model.getTile(z, x, y);
				int under = uBlend[x][y];
				int over = showOverlay ? overColor(tile) : -1;
				int overTex = showOverlay ? overTexture(tile) : -1;

				float x0 = x * TILE, x1 = (x + 1) * TILE;
				float z0 = y * TILE, z1 = (y + 1) * TILE;

				// Corner light values (l00 = (x,y), l10 = (x+1,y), l01 = (x,y+1), l11 = (x+1,y+1)).
				float l00 = light[x][y], l10 = light[x + 1][y];
				float l01 = light[x][y + 1], l11 = light[x + 1][y + 1];

				int path = tile.overlayPath & 0xFF;
				double[][] overPoly = (over >= 0 && path > 0)
					? MapRenderer.shapePolygon(path, tile.overlayRotation & 3, true) : null;

				if (overPoly == null)
				{
					int base = over >= 0 ? over : under;
					if (base < 0)
					{
						continue;
					}
					// Only a full-tile overlay carries the texture; a bare underlay is flat.
					int tex = over >= 0 ? overTex : -1;
					addTerrainTri(scene, x0, h00, z0, x1, h11, z1, x0, h01, z1, base, tex,
						0, 0, 1, 1, 0, 1, l00, l11, l01);
					addTerrainTri(scene, x0, h00, z0, x1, h10, z0, x1, h11, z1, base, tex,
						0, 0, 1, 0, 1, 1, l00, l10, l11);
					continue;
				}

				// Shaped overlay: emit the exact cut polygons (clean diagonals),
				// heights + light interpolated across the tile.
				emitPolyFan(scene, overPoly, over, overTex, x0, z0, h00, h10, h01, h11, l00, l10, l01, l11);
				if (under >= 0)
				{
					double[][] underPoly =
						MapRenderer.shapePolygon(path, tile.overlayRotation & 3, false);
					emitPolyFan(scene, underPoly, under, -1, x0, z0, h00, h10, h01, h11, l00, l10, l01, l11);
				}
			}
		}
	}

	/**
	 * Smooth per-vertex light from the heightmap: the surface normal at each vertex
	 * (from neighbouring heights) dotted with the scene light direction, mapped to a
	 * brightness multiplier. Interpolating this across triangles gives Gouraud shading.
	 */
	private float[][] computeVertexLight(Region region, int z)
	{
		float[][] L = new float[X][Y];
		for (int x = 0; x < X; x++)
		{
			for (int y = 0; y < Y; y++)
			{
				int hl = region.getTileHeight(z, Math.max(0, x - 1), y);
				int hr = region.getTileHeight(z, Math.min(X - 1, x + 1), y);
				int hd = region.getTileHeight(z, x, Math.max(0, y - 1));
				int hu = region.getTileHeight(z, x, Math.min(Y - 1, y + 1));
				// Normal ~ (-(dH/dx), up, -(dH/dz)); scale keeps physical slope proportions.
				double nx = hr - hl;
				double ny = 2.0 * TILE;
				double nz = hu - hd;
				double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
				double ndotl = len > 0 ? Math.abs(nx * LX + ny * LY + nz * LZ) / (len * LLEN) : 0;
				double factor = 0.55 + 0.45 * ndotl;
				L[x][y] = (float) (factor > 1.2 ? 1.2 : factor);
			}
		}
		return L;
	}

	/**
	 * Neighbour-averaged underlay colours (an approximation of the OSRS ground blend),
	 * so adjacent floor colours fade into each other instead of forming hard blocks.
	 * Returns -1 for tiles with no underlay.
	 */
	private int[][] computeBlendedUnderlay(RegionModel model, int z)
	{
		int[][] raw = new int[X][Y];
		for (int x = 0; x < X; x++)
		{
			for (int y = 0; y < Y; y++)
			{
				raw[x][y] = underColor(model.getTile(z, x, y));
			}
		}
		final int R = 5; // 11x11 kernel, similar spread to the client's ground blend
		int[][] out = new int[X][Y];
		for (int x = 0; x < X; x++)
		{
			for (int y = 0; y < Y; y++)
			{
				if (raw[x][y] < 0)
				{
					out[x][y] = -1;
					continue;
				}
				long rs = 0, gs = 0, bs = 0;
				int n = 0;
				for (int dx = -R; dx <= R; dx++)
				{
					int xx = x + dx;
					if (xx < 0 || xx >= X)
					{
						continue;
					}
					for (int dy = -R; dy <= R; dy++)
					{
						int yy = y + dy;
						if (yy < 0 || yy >= Y)
						{
							continue;
						}
						int c = raw[xx][yy];
						if (c < 0)
						{
							continue;
						}
						rs += (c >> 16) & 0xFF;
						gs += (c >> 8) & 0xFF;
						bs += c & 0xFF;
						n++;
					}
				}
				out[x][y] = n > 0
					? (((int) (rs / n)) << 16) | (((int) (gs / n)) << 8) | ((int) (bs / n))
					: raw[x][y];
			}
		}
		return out;
	}

	private static double bilerp(double h00, double h10, double h01, double h11, double fx, double fz)
	{
		return h00 * (1 - fx) * (1 - fz) + h10 * fx * (1 - fz)
			+ h01 * (1 - fx) * fz + h11 * fx * fz;
	}

	/**
	 * Triangulate a unit-square polygon (x right, y down-from-NORTH, wound CW in
	 * y-down coords) onto the tile at world (x0, z0..z0+TILE), colour c.
	 */
	private void emitPolyFan(Renderer3D.Scene scene, double[][] poly, int c, int tex,
		float x0, float z0, int h00, int h10, int h01, int h11,
		float l00, float l10, float l01, float l11)
	{
		if (poly == null || c < 0)
		{
			return;
		}
		int n = poly.length;
		float[] wx = new float[n], wy = new float[n], wz = new float[n];
		float[] uu = new float[n], vv = new float[n], ll = new float[n];
		for (int i = 0; i < n; i++)
		{
			double fx = poly[i][0];
			double fz = 1.0 - poly[i][1]; // polygon y counts from the north edge
			wx[i] = (float) (x0 + fx * TILE);
			wz[i] = (float) (z0 + fz * TILE);
			wy[i] = (float) bilerp(h00, h10, h01, h11, fx, fz);
			uu[i] = (float) poly[i][0];
			vv[i] = (float) poly[i][1]; // texture v = north-referenced polygon y
			ll[i] = (float) bilerp(l00, l10, l01, l11, fx, fz);
		}
		for (int i = 1; i < n - 1; i++)
		{
			addTerrainTri(scene, wx[0], wy[0], wz[0],
				wx[i], wy[i], wz[i], wx[i + 1], wy[i + 1], wz[i + 1], c, tex,
				uu[0], vv[0], uu[i], vv[i], uu[i + 1], vv[i + 1], ll[0], ll[i], ll[i + 1]);
		}
	}

	private int underColor(net.runelite.cache.definitions.MapDefinition.Tile tile)
	{
		int underlayId = tile.underlayId & 0xFFFF;
		if (underlayId == 0)
		{
			return -1;
		}
		// Tile stores the id as (definition + 1); the real underlay definition is id-1 (like OSRS).
		UnderlayDefinition u = service.getUnderlay(underlayId - 1);
		return u != null ? (u.getColor() & 0xFFFFFF) : -1;
	}

	private int overColor(net.runelite.cache.definitions.MapDefinition.Tile tile)
	{
		int overlayId = tile.overlayId & 0xFFFF;
		if (overlayId == 0)
		{
			return -1;
		}
		// Tile stores the id as (definition + 1); the real overlay definition is id-1 (like OSRS).
		OverlayDefinition o = service.getOverlay(overlayId - 1);
		if (o == null)
		{
			return -1;
		}
		// Magenta (0xFF00FF) is OSRS's "hidden/transparent overlay" marker: the tile shows the
		// underlay (its shape still applies), so don't draw the literal magenta as bright pink.
		if ((o.getRgbColor() & 0xFFFFFF) == 0xFF00FF)
		{
			return -1;
		}
		boolean textured = o.getTexture() != -1 && service.hasTextures();
		if (renderTextures && textured)
		{
			return service.getTextureAverage(o.getTexture()) & 0xFFFFFF;
		}
		if (textured)
		{
			// Textures off: match a client that draws the overlay's flat colour instead of the
			// texture (e.g. the black checker tile is rgb #000000 — keep it black, no fallback).
			return o.getRgbColor() & 0xFFFFFF;
		}
		int orgb = o.getRgbColor();
		if (orgb == 0 && o.getSecondaryRgbColor() != -1)
		{
			orgb = o.getSecondaryRgbColor();
		}
		return orgb != 0 ? (orgb & 0xFFFFFF) : 0x5a6b3a;
	}

	/** The texture id painted on this tile via its overlay, or -1 for a flat colour. */
	private int overTexture(net.runelite.cache.definitions.MapDefinition.Tile tile)
	{
		if (!renderTextures || !service.hasTextures())
		{
			return -1;
		}
		int overlayId = tile.overlayId & 0xFFFF;
		if (overlayId == 0)
		{
			return -1;
		}
		OverlayDefinition o = service.getOverlay(overlayId - 1); // stored id is definition+1
		if (o == null || o.getTexture() == -1)
		{
			return -1;
		}
		return service.getTexturePixels(o.getTexture()) != null ? o.getTexture() : -1;
	}

	/**
	 * Emit one terrain triangle with smooth (Gouraud) per-vertex lighting, texture-mapped
	 * when {@code tex >= 0}, else flat. The la/lb/lc are the light multipliers at each vertex.
	 */
	private void addTerrainTri(Renderer3D.Scene scene,
		float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
		int rgb, int tex, float ua, float va, float ub, float vb, float uc, float vc,
		float la, float lb, float lc)
	{
		if (tex >= 0)
		{
			scene.addSmoothTextured(ax, ay, az, bx, by, bz, cx, cy, cz,
				tex, service.getTextureAverage(tex), ua, va, ub, vb, uc, vc, la, lb, lc);
		}
		else
		{
			scene.addSmooth(ax, ay, az, bx, by, bz, cx, cy, cz, rgb, la, lb, lc);
		}
	}

	private void addLitTriangle(Renderer3D.Scene scene, float ax, float ay, float az,
		float bx, float by, float bz, float cx, float cy, float cz, int rgb)
	{
		double factor = shadeFactor(ax, ay, az, bx, by, bz, cx, cy, cz, 0.55, 0.45);
		scene.add(ax, ay, az, bx, by, bz, cx, cy, cz, shadeApply(rgb, factor));
	}

	// ---- objects -------------------------------------------------------

	// Object filters for the animation split: ALL (normal), STATIC (skip animated), ANIMATED (only).
	static final int FILTER_ALL = 0, FILTER_STATIC = 1, FILTER_ANIMATED = 2;

	// Deob wall-decoration offset direction tables (Tiles.field518/515 for straight type 5,
	// field509/517 for diagonal types 6/8), indexed by orientation. See class7.java.
	private static final int[] WD5_X = {1, 0, -1, 0},  WD5_Z = {0, -1, 0, 1};
	private static final int[] WD6_X = {1, -1, -1, 1}, WD6_Z = {-1, -1, 1, 1};

	// Tile key (x<<6|y) → the wall's decorDisplacement (int2) on the current plane, so type-5/6/8
	// wall decorations offset by the WALL'S width, matching the deob client.
	private final java.util.Map<Integer, Integer> wallDisplaceAt = new java.util.HashMap<>();

	private void buildWallLookup(RegionModel model, int plane)
	{
		wallDisplaceAt.clear();
		for (Location loc : model.getLocations().getLocations())
		{
			if (loc.getPosition().getZ() != plane) { continue; }
			int t = loc.getType();
			if (t > 3 && t != 9) { continue; } // walls
			int x = loc.getPosition().getX(), y = loc.getPosition().getY();
			if (x < 0 || x >= 64 || y < 0 || y >= 64) { continue; }
			ObjectDefinition wd = service.getObjectDefs().get(loc.getId());
			wallDisplaceAt.put((x << 6) | y, wd != null ? wd.getDecorDisplacement() : 16);
		}
	}



	private void buildObjects(Renderer3D.Scene scene, RegionModel model, Region region, int plane, int animTick)
	{
		buildObjects(scene, model, region, plane, animTick, FILTER_ALL);
	}

	private void buildObjects(Renderer3D.Scene scene, RegionModel model, Region region, int plane, int animTick, int filter)
	{
		buildWallLookup(model, plane);
		for (Location loc : model.getLocations().getLocations())
		{
			if (loc.getPosition().getZ() != plane)
			{
				continue;
			}
			ObjectDefinition def = service.getObjectDefs().get(loc.getId());
			if (def == null)
			{
				continue;
			}
			// Split animated vs static objects so the animation loop only rebuilds the animated ones.
			boolean animated = def.getAnimationID() >= 0;
			if (filter == FILTER_STATIC && animated) { continue; }
			if (filter == FILTER_ANIMATED && !animated) { continue; }
			int type = loc.getType();
			if (layers != null && !MapRenderer.catVisible(layers, type, def)) { continue; }
			int flat = -1;
			if (highlight != null)
			{
				if (loc == highlight)
				{
					flat = 0xFFEE33; // the selected object
				}
				else if (loc.getId() == highlight.getId())
				{
					flat = 0xE07818; // every other placement of the same object id
				}
			}
			placeObjectModel(scene, region, plane, loc.getPosition().getX(), loc.getPosition().getY(),
				loc.getType(), loc.getOrientation(), def, flat, animTick);
		}
	}

	/** Object-only scene, each location flat-coloured by its index+1 for picking. */
	public Renderer3D.Scene buildObjectPickScene(RegionModel model, int plane, java.util.List<Location> outList)
	{
		Renderer3D.Scene scene = new Renderer3D.Scene();
		if (service.getObjectDefs() == null || service.getModels() == null)
		{
			return scene;
		}
		Region region = new Region(model.getRegionId());
		region.loadTerrain(model.getMap());

		for (Location loc : model.getLocations().getLocations())
		{
			if (loc.getPosition().getZ() != plane)
			{
				continue;
			}
			ObjectDefinition def = service.getObjectDefs().get(loc.getId());
			if (def == null)
			{
				continue;
			}
			// Honour the same Hide/Show filters as rendering, so a hidden category can't be clicked
			// and the pick falls through to the object beneath it.
			if (layers != null && !MapRenderer.catVisible(layers, loc.getType(), def)) { continue; }
			int id = outList.size() + 1; // +1 so 0 == miss
			if (placeObjectModel(scene, region, plane, loc.getPosition().getX(), loc.getPosition().getY(),
				loc.getType(), loc.getOrientation(), def, id, -1))
			{
				outList.add(loc);
			}
		}
		return scene;
	}

	private boolean placeObjectModel(Renderer3D.Scene scene, Region region, int plane,
		int lx, int ly, int type, int orientation, ObjectDefinition def, int flatColor, int animTick)
	{
		int[] allModels = def.getObjectModels();
		if (allModels == null || allModels.length == 0)
		{
			return false;
		}
		int[] types = def.getObjectTypes();
		// Multi-PART objects (types==null, e.g. the Portal with two models) render ALL their
		// models together as one object. Multi-TYPE objects (walls) pick the single model that
		// matches the location type.
		int[] emitIds;
		if (types == null)
		{
			emitIds = allModels;
		}
		else
		{
			int mid = pickModel(def, type);
			if (mid < 0) { return false; }
			emitIds = new int[]{mid};
		}

		orientation &= 3;
		int sizeX = def.getSizeX();
		int sizeY = def.getSizeY();
		if (orientation == 1 || orientation == 3)
		{
			int t = sizeX; sizeX = sizeY; sizeY = t;
		}
		double centerX = lx * TILE + sizeX * TILE / 2.0;
		double centerZ = ly * TILE + sizeY * TILE / 2.0;
		double baseH = footprintHeight(region, plane, lx, ly, sizeX, sizeY);

		// Wall decorations (types 4-8): deob placement (class7.java) uses field518/515 for type 5, but
		// with 0 offset for type 4. In the game walls are thin planes so type 4 shows at the wall; the
		// editor draws SOLID thick wall models that swallow it, so we nudge type 4 the same way (toward
		// the room, field518/515 direction) just less than type 5. Editor-only rendering (never saved).
		if (type == 4 || type == 5)
		{
			// Small nudge off the wall (toward the room) so the decoration isn't buried in the
			// editor's solid thick wall models. Same treatment for ALL type-4 decorations.
			double out = TILE * (type == 5 ? 0.3 : 0.22); // type 5 sits a touch further out than type 4
			centerX += WD5_X[orientation & 3] * out;
			centerZ += WD5_Z[orientation & 3] * out;
		}
		else if (type == 6 || type == 8)
		{
		  Integer wd = wallDisplaceAt.get((lx << 6) | ly);
		  double disp = ((wd != null) ? wd : 16) / 2.0; // int2 / 2 (default 8)
		  centerX += WD6_X[orientation & 3] * disp;
		  centerZ += WD6_Z[orientation & 3] * disp;
		}

		boolean contour = def.getContouredGround() != -1;
		Region cr = contour ? region : null;
		double msx = def.getModelSizeX() / 128.0, msy = def.getModelSizeHeight() / 128.0, msz = def.getModelSizeY() / 128.0;
		// DISPLAY-ONLY rotation override for this object id (editor view only; never saved).
		Integer dr = displayRot.get(def.getId());
		if (dr != null) { orientation = (orientation + dr) & 3; }

		// Rotation/mirror. Type 11 scenery = +45° (DIAGONAL). Diagonal wall decorations (6/7/8) use the
		// deob's getEntity(4, orientation+4): the +4 TOGGLES the isRotated mirror (var2>3), and type 7
		// rotates by (orientation+2)&3 — NOT a 45° rotation. isRotated (opcode 62) mirrors across Z.
		int emitOrient;
		boolean mirror = def.isRotated();
		if (type == 11)
		{
			emitOrient = orientation | DIAGONAL;
		}
		else if (type == 6 || type == 8)
		{
			emitOrient = orientation; mirror = !mirror;
		}
		else if (type == 7)
		{
			emitOrient = (orientation + 2) & 3; mirror = !mirror;
		}
		else
		{
			emitOrient = orientation;
		}
		if (mirror) { emitOrient |= MIRROR; }

		boolean any = false;
		for (int mid : emitIds)
		{
			ModelDefinition m = service.getModels().get(mid);
			if (m == null || m.vertexCount == 0)
			{
				continue;
			}
			if (animTick >= 0 && flatColor < 0 && def.getAnimationID() >= 0 && service.getAnimations() != null)
			{
				m = service.getAnimations().pose(m, def.getAnimationID(), animTick);
			}
			emitModel(scene, m, centerX, centerZ, baseH, emitOrient, msx, msy, msz, flatColor,
				def.getRecolorToFind(), def.getRecolorToReplace(), def.getRetextureToFind(), def.getTextureToReplace(),
				cr, plane);
			// OSRS "L-corner" walls (type 2) draw the wall model on TWO adjacent edges.
			if (types != null && type == 2)
			{
				emitModel(scene, m, centerX, centerZ, baseH, ((orientation + 1) & 3) | (def.isRotated() ? MIRROR : 0), msx, msy, msz, flatColor,
					def.getRecolorToFind(), def.getRecolorToReplace(), def.getRetextureToFind(), def.getTextureToReplace(),
					cr, plane);
			}
			any = true;
		}
		return any;
	}

	private static int pickModel(ObjectDefinition def, int type)
	{
		int[] models = def.getObjectModels();
		if (models == null || models.length == 0)
		{
			return -1;
		}
		int[] types = def.getObjectTypes();
		if (types == null)
		{
			return models[0];
		}
		for (int i = 0; i < types.length && i < models.length; i++)
		{
			if (types[i] == type)
			{
				return models[i];
			}
		}
		return models[0];
	}

	// ---- spawns (server NPCs / objects) --------------------------------

	private void buildSpawns(Renderer3D.Scene scene, RegionModel model, Region region, int plane,
		java.util.List<SpawnLoader.Spawn> spawns, int animTick)
	{
		for (SpawnLoader.Spawn s : spawns)
		{
			if (s.z != plane)
			{
				continue;
			}
			int lx = s.x - model.getBaseX();
			int ly = s.y - model.getBaseY();
			if (lx < 0 || lx > 63 || ly < 0 || ly > 63)
			{
				continue;
			}
			double cx = lx * TILE + TILE / 2.0;
			double cz = ly * TILE + TILE / 2.0;
			double h = region.getTileHeight(plane, lx, ly);

			if (s.npc)
			{
				if (!placeNpcModel(scene, cx, cz, h, s, animTick))
				{
					pole(scene, cx, h, cz, 320, 26, 0xFF3030);
				}
			}
			else
			{
				ObjectDefinition def = service.getObjectDefs() != null ? service.getObjectDefs().get(s.id) : null;
				if (def == null || !placeObjectModel(scene, region, plane, lx, ly, s.type, s.orientation, def, -1, animTick))
				{
					pole(scene, cx, h, cz, 200, 22, 0x30E0FF);
				}
			}
		}
	}

	/**
	 * Places the server-source object spawns (ClientObj.java / HomeHandler.java, from
	 * {@link MapEditorService#getServerSpawns}) into the 3D scene for this plane, so they render
	 * as real models alongside the cache objects — matching the 2D "Server objects" overlay.
	 */
	private void buildServerSpawns(Renderer3D.Scene scene, RegionModel model, Region region, int plane, int animTick)
	{
		for (int[] s : service.getServerSpawns())
		{
			int id = s[0], x = s[1], y = s[2], z = s[3], type = s[4], rot = s[5];
			if (z != plane)
			{
				continue;
			}
			int lx = x - model.getBaseX();
			int ly = y - model.getBaseY();
			if (lx < 0 || lx > 63 || ly < 0 || ly > 63)
			{
				continue;
			}
			ObjectDefinition def = service.getObjectDefs().get(id);
			if (def == null)
			{
				continue;
			}
			if (!placeObjectModel(scene, region, plane, lx, ly, type, rot, def, -1, animTick))
			{
				double cx = lx * TILE + TILE / 2.0;
				double cz = ly * TILE + TILE / 2.0;
				double h = region.getTileHeight(plane, lx, ly);
				pole(scene, cx, h, cz, 200, 22, 0xB45AFF); // purple marker for a modelless server spawn
			}
		}
	}

	private boolean placeNpcModel(Renderer3D.Scene scene, double cx, double cz, double baseH,
		SpawnLoader.Spawn s, int animTick)
	{
		NpcDefinition def = service.getNpc(s.id);
		if (def == null || def.models == null || def.models.length == 0 || service.getModels() == null)
		{
			return false;
		}
		boolean animate = animTick >= 0 && def.standingAnimation >= 0 && service.getAnimations() != null;
		boolean any = false;
		for (int mid : def.models)
		{
			ModelDefinition m = service.getModels().get(mid);
			if (m != null && m.vertexCount > 0)
			{
				if (animate)
				{
					m = service.getAnimations().pose(m, def.standingAnimation, animTick);
				}
				emitModel(scene, m, cx, cz, baseH, s.orientation, 1.0, 1.0, 1.0, -1);
				any = true;
			}
		}
		return any;
	}

	// ---- shared model emit (textured or flat) --------------------------

	private void emitModel(Renderer3D.Scene scene, ModelDefinition m, double centerX, double centerZ,
		double baseH, int orientation, double sx, double sy, double sz, int flatColor)
	{
		emitModel(scene, m, centerX, centerZ, baseH, orientation, sx, sy, sz, flatColor,
			null, null, null, null, null, 0);
	}

	// Object/NPC definitions recolour and retexture their shared model at build time
	// (recolorToFind→recolorToReplace HSL swaps, retextureToFind→textureToReplace). Many
	// plants/bushes store dark base colours that only become green via recolour, so we must
	// apply these per-object instead of mutating the cached ModelDefinition. When contourRegion
	// is non-null the model is draped onto the terrain (contouredGround objects such as steps).
	private void emitModel(Renderer3D.Scene scene, ModelDefinition m, double centerX, double centerZ,
		double baseH, int orientation, double sx, double sy, double sz, int flatColor,
		short[] recolorFind, short[] recolorReplace, short[] retexFind, short[] retexReplace,
		Region contourRegion, int contourPlane)
	{
		boolean canTexture = renderTextures && flatColor < 0 && service.hasTextures()
			&& m.faceTextures != null && m.faceTextureUCoordinates != null;

		// isRotated mirror (across Z): the reflection flips triangle winding, so we reverse it
		// here (swap corners a/c) to keep normals/back-faces correct — matching the client's
		// indices1↔indices3 swap in ModelData.method2817. vertex()/vertexLight() negate Z.
		boolean mirror = (orientation & MIRROR) != 0;

		for (int f = 0; f < m.faceCount; f++)
		{
			if (m.faceRenderTypes != null && m.faceRenderTypes[f] == 2)
			{
				continue;
			}
			int a = m.faceIndices1[f], b = m.faceIndices2[f], c = m.faceIndices3[f];
			if (mirror) { int t = a; a = c; c = t; }
			double[] va = vertex(m, a, sx, sy, sz, orientation, centerX, centerZ, baseH, contourRegion, contourPlane);
			double[] vb = vertex(m, b, sx, sy, sz, orientation, centerX, centerZ, baseH, contourRegion, contourPlane);
			double[] vc = vertex(m, c, sx, sy, sz, orientation, centerX, centerZ, baseH, contourRegion, contourPlane);

			// Solid colour override (object picking id, or selection highlight).
			if (flatColor >= 0)
			{
				scene.add((float) va[0], (float) va[1], (float) va[2],
					(float) vb[0], (float) vb[1], (float) vb[2],
					(float) vc[0], (float) vc[1], (float) vc[2], flatColor);
				continue;
			}

			// Per-vertex light for smooth (Gouraud) model shading. Faces flagged flat
			// (render type 1) light all three corners from the face normal; smooth faces
			// use the model's averaged per-vertex normals rotated into world space.
			float[][] vn = vertexNormals(m);
			float la, lb, lc;
			if (m.faceRenderTypes != null && m.faceRenderTypes[f] == 1)
			{
				float ff = (float) shadeFactor(va[0], va[1], va[2], vb[0], vb[1], vb[2],
					vc[0], vc[1], vc[2], 0.6, 0.4);
				la = lb = lc = ff;
			}
			else
			{
				la = (float) vertexLight(vn[a], orientation);
				lb = (float) vertexLight(vn[b], orientation);
				lc = (float) vertexLight(vn[c], orientation);
			}

			short tex = canTexture ? m.faceTextures[f] : -1;
			if (tex >= 0 && retexFind != null)
			{
				for (int i = 0; i < retexFind.length; i++)
				{
					if (retexFind[i] == tex) { tex = retexReplace[i]; break; }
				}
			}
			if (tex >= 0 && m.faceTextureUCoordinates[f] != null && service.getTexturePixels(tex) != null)
			{
				float[] u = m.faceTextureUCoordinates[f];
				float[] v = m.faceTextureVCoordinates[f];
				scene.addSmoothTextured((float) va[0], (float) va[1], (float) va[2],
					(float) vb[0], (float) vb[1], (float) vb[2],
					(float) vc[0], (float) vc[1], (float) vc[2],
					tex, service.getTextureAverage(tex),
					u[0], v[0], u[1], v[1], u[2], v[2], la, lb, lc);
			}
			else if (m.faceColors != null)
			{
				short col = m.faceColors[f];
				if (recolorFind != null)
				{
					for (int i = 0; i < recolorFind.length; i++)
					{
						if (recolorFind[i] == col) { col = recolorReplace[i]; break; }
					}
				}
				int rgb = JagexColor.HSLtoRGB(col, JagexColor.BRIGHTNESS_LOW);
				scene.addSmooth((float) va[0], (float) va[1], (float) va[2],
					(float) vb[0], (float) vb[1], (float) vb[2],
					(float) vc[0], (float) vc[1], (float) vc[2], rgb, la, lb, lc);
			}
		}
	}

	/** Averaged, unit per-vertex normals in model space (cached per model). */
	private float[][] vertexNormals(ModelDefinition m)
	{
		float[][] cached = normalCache.get(m);
		if (cached != null)
		{
			return cached;
		}
		float[][] vn = new float[m.vertexCount][3];
		for (int f = 0; f < m.faceCount; f++)
		{
			if (m.faceRenderTypes != null && m.faceRenderTypes[f] == 2)
			{
				continue;
			}
			int a = m.faceIndices1[f], b = m.faceIndices2[f], c = m.faceIndices3[f];
			double ux = m.vertexX[b] - m.vertexX[a], uy = m.vertexY[b] - m.vertexY[a], uz = m.vertexZ[b] - m.vertexZ[a];
			double wx = m.vertexX[c] - m.vertexX[a], wy = m.vertexY[c] - m.vertexY[a], wz = m.vertexZ[c] - m.vertexZ[a];
			// Area-weighted face normal (not normalised) so larger faces influence more.
			float nx = (float) (uy * wz - uz * wy);
			float ny = (float) (uz * wx - ux * wz);
			float nz = (float) (ux * wy - uy * wx);
			vn[a][0] += nx; vn[a][1] += ny; vn[a][2] += nz;
			vn[b][0] += nx; vn[b][1] += ny; vn[b][2] += nz;
			vn[c][0] += nx; vn[c][1] += ny; vn[c][2] += nz;
		}
		for (float[] n : vn)
		{
			double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (len > 0)
			{
				n[0] /= len; n[1] /= len; n[2] /= len;
			}
		}
		normalCache.put(m, vn);
		return vn;
	}

	/** Light factor for a model-space vertex normal rotated by the placement orientation. */
	private static double vertexLight(float[] n, int orientation)
	{
		double nx = n[0], ny = n[1], nz = n[2], rx, rz;
		// Match the isRotated Z-mirror applied to vertices so lighting stays consistent.
		if ((orientation & MIRROR) != 0) { nz = -nz; }
		switch (orientation & 3)
		{
			case 1: rx = nz;  rz = -nx; break;
			case 2: rx = -nx; rz = -nz; break;
			case 3: rx = -nz; rz = nx;  break;
			default: rx = nx; rz = nz;
		}
		return lightForNormal(rx, ny, rz, 0.6, 0.4);
	}

	private double[] vertex(ModelDefinition m, int i, double sx, double sy, double sz,
		int orientation, double centerX, double centerZ, double baseH,
		Region contourRegion, int contourPlane)
	{
		double vx = m.vertexX[i] * sx;
		double vy = m.vertexY[i] * sy;
		double vz = m.vertexZ[i] * sz;

		// isRotated: mirror across Z in model space BEFORE the placement rotation.
		if ((orientation & MIRROR) != 0) { vz = -vz; }

		double rx, rz;
		switch (orientation & 3)
		{
			case 1: rx = vz;  rz = -vx; break;
			case 2: rx = -vx; rz = -vz; break;
			case 3: rx = -vz; rz = vx;  break;
			default: rx = vx; rz = vz;
		}
		// Diagonal (type 11) scenery: rotate an extra 45° in the same direction as the 90° steps.
		if ((orientation & DIAGONAL) != 0)
		{
			double c = 0.7071067811865476;
			double nrx = c * rx + c * rz;
			double nrz = -c * rx + c * rz;
			rx = nrx;
			rz = nrz;
		}
		double wx = centerX + rx, wz = centerZ + rz;
		// Contoured-ground objects (steps, paths, some fences) bend to the terrain: each
		// vertex sits on the interpolated ground height at its own XZ instead of one flat base.
		double base = contourRegion != null ? groundHeight(contourRegion, contourPlane, wx, wz) : baseH;
		return new double[]{wx, base + vy, wz};
	}

	/** Bilinearly interpolated terrain height at a world XZ (units of TILE), for ground contouring. */
	private double groundHeight(Region region, int plane, double worldX, double worldZ)
	{
		double tx = worldX / TILE, ty = worldZ / TILE;
		int x0 = (int) Math.floor(tx), y0 = (int) Math.floor(ty);
		double fx = tx - x0, fy = ty - y0;
		double h00 = region.getTileHeight(plane, clamp(x0),     clamp(y0));
		double h10 = region.getTileHeight(plane, clamp(x0 + 1), clamp(y0));
		double h01 = region.getTileHeight(plane, clamp(x0),     clamp(y0 + 1));
		double h11 = region.getTileHeight(plane, clamp(x0 + 1), clamp(y0 + 1));
		double a = h00 + (h10 - h00) * fx;
		double b = h01 + (h11 - h01) * fx;
		return a + (b - a) * fy;
	}

	private double footprintHeight(Region region, int z, int lx, int ly, int sizeX, int sizeY)
	{
		int x0 = clamp(lx), y0 = clamp(ly);
		int x1 = clamp(lx + sizeX), y1 = clamp(ly + sizeY);
		double sum = region.getTileHeight(z, x0, y0) + region.getTileHeight(z, x1, y0)
			+ region.getTileHeight(z, x0, y1) + region.getTileHeight(z, x1, y1);
		return sum / 4.0;
	}

	private static int clamp(int v)
	{
		return v < 0 ? 0 : (v > 63 ? 63 : v);
	}

	// ---- spawn pole fallback -------------------------------------------

	private void pole(Renderer3D.Scene scene, double cx, double baseH, double cz,
		double height, double halfW, int color)
	{
		double top = baseH - height;
		quadBothSides(scene, cx - halfW, baseH, cz, cx + halfW, baseH, cz,
			cx + halfW, top, cz, cx - halfW, top, cz, color);
		quadBothSides(scene, cx, baseH, cz - halfW, cx, baseH, cz + halfW,
			cx, top, cz + halfW, cx, top, cz - halfW, color);
	}

	private void quadBothSides(Renderer3D.Scene scene,
		double x0, double y0, double z0, double x1, double y1, double z1,
		double x2, double y2, double z2, double x3, double y3, double z3, int color)
	{
		scene.add((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1,
			(float) x2, (float) y2, (float) z2, color);
		scene.add((float) x0, (float) y0, (float) z0, (float) x2, (float) y2, (float) z2,
			(float) x3, (float) y3, (float) z3, color);
		scene.add((float) x0, (float) y0, (float) z0, (float) x2, (float) y2, (float) z2,
			(float) x1, (float) y1, (float) z1, color);
		scene.add((float) x0, (float) y0, (float) z0, (float) x3, (float) y3, (float) z3,
			(float) x2, (float) y2, (float) z2, color);
	}

	// ---- lighting ------------------------------------------------------

	private static double shadeFactor(double ax, double ay, double az, double bx, double by, double bz,
		double cx, double cy, double cz, double ambient, double diffuse)
	{
		double nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
		double ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
		double nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
		return lightForNormal(nx, ny, nz, ambient, diffuse);
	}

	/** Diffuse-plus-ambient brightness for a (non-unit) normal against the scene light. */
	private static double lightForNormal(double nx, double ny, double nz, double ambient, double diffuse)
	{
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		double ndotl = len > 0 ? Math.abs(nx * LX + ny * LY + nz * LZ) / (len * LLEN) : 0;
		double factor = ambient + diffuse * ndotl;
		return factor > 1.2 ? 1.2 : factor;
	}

	private static int shadeApply(int rgb, double factor)
	{
		int r = clampByte((int) (((rgb >> 16) & 0xFF) * factor));
		int g = clampByte((int) (((rgb >> 8) & 0xFF) * factor));
		int b = clampByte((int) ((rgb & 0xFF) * factor));
		return (r << 16) | (g << 8) | b;
	}

	private static int clampByte(int v)
	{
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}
