/*
 * Renders a single plane of a RegionModel to a BufferedImage, top-down, the way
 * a 2D map editor shows it: underlay colour per tile, overlay colour on top
 * (drawn with the real OSRS tile SHAPES — diagonals, corners, halves — from the
 * overlay path + rotation, exactly like the official map), optional
 * collision-flag tint, a faint grid, and markers for scenery/walls.
 *
 * The shape masks are ported from RuneLite's MapImageDumper (generateTileShapes)
 * but generated at a higher resolution (SHAPE_SCALE) for smoother diagonals at
 * editor zoom levels.
 *
 * North (+y) is up, so tile y is flipped when drawing to screen coordinates.
 */
package net.runelite.cache.editor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import static net.runelite.cache.region.Region.X;
import static net.runelite.cache.region.Region.Y;

public class MapRenderer
{
	private static final Color VOID = new Color(0x1e1e1e);
	private static final Color GRID = new Color(0, 0, 0, 40);
	private static final Color WALL = new Color(0xF0F0F0);
	// Walls drawn all white (shape still distinguishes type), matching the official OSRS map editor.
	private static final Color WALL_STRAIGHT = new Color(0xF0F0F0);
	private static final Color WALL_CORNER = new Color(0xF0F0F0);
	private static final Color WALL_DIAGONAL = new Color(0xF0F0F0);
	private static final Color WALL_SQUARE = new Color(0xF0F0F0);
	private static final Color SCENERY = new Color(255, 140, 0, 170);
	private static final Color GROUND_DECOR = new Color(120, 200, 255, 190);
	private static final Color BLOCKED = new Color(220, 40, 40, 90);
	// Per-category object marker colours (loosely matching the official editor's element groups):
	private static final Color CAT_SCENERY_FILL = new Color(255, 150, 40, 205); // scenery (orange)
	private static final Color CAT_SCENERY_EDGE = new Color(120, 70, 20);
	private static final Color CAT_ROOF_FILL    = new Color(200, 120, 235, 205); // roofs (purple)
	private static final Color CAT_ROOF_EDGE    = new Color(90, 45, 120);
	private static final Color CAT_WALLDECOR     = new Color(110, 215, 235, 230); // wall decoration (cyan)
	// White outline stamped on overlay tiles so they read clearly against the underlay
	// (as in the official editor). Kept semi-transparent so it isn't harsh.
	private static final Color OVERLAY_OUTLINE = new Color(255, 255, 255, 130);

	/** Resolution of the tile-shape masks (higher = smoother diagonals). */
	public static final int SHAPE_SCALE = 8;
	private static byte[][][] TILE_SHAPES; // [shape 1..8 -1][rotation][row*S+col]

	private final MapEditorService service;

	public MapRenderer(MapEditorService service)
	{
		this.service = service;
	}

	public static class Options
	{
		public boolean showGrid = true;
		public boolean showObjects = true;
		public boolean showFlags = false;
		// Hide/Show Elements (like the official editor's panel) — per category.
		public boolean showWalls = true;       // wall types 0-3, 9 (non-door)
		public boolean showDoors = true;       // wall-like objects named door/gate
		public boolean showWallDecor = true;   // wall decoration types 4-8
		public boolean showScenery = true;     // scenery types 10-11
		public boolean showRoofs = true;       // roof types 12-21
		public boolean showGroundDecor = true; // ground decoration type 22
		public boolean showIcons = true;       // objects carrying a map_icon (mapAreaId)
		public boolean showOverlay = true;
		// Height-mode relief tint (low = blue, high = red), like the official
		// editor's height view.
		public boolean showHeightTint = false;
		// Merge all 4 planes into one view.
		public boolean allPlanes = false;
		public boolean showWorldMapIcons = false;
		public boolean showObjectMapIcons = false;
		public boolean showServerSpawns = false;
		// "Onion-skin": when editing an upper plane, show the planes below it
		// dimmed underneath (so you can line up walls with what's on plane 0).
		public boolean ghostLowerPlanes = true;
	}

	/** How dark the ghosted lower planes are pushed (0 = none, 255 = black). */
	private static final int GHOST_VEIL_ALPHA = 150;

	public BufferedImage render(RegionModel model, int plane, int tileSize, Options opt)
	{
		ensureShapes();

		int size = X * tileSize;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Onion-skin: when editing an upper plane, draw everything below it first
		// then push it back with a dark veil, so the current plane stands out but
		// you can still see where the lower-floor walls/rooms are.
		boolean ghost = opt.ghostLowerPlanes && !opt.allPlanes && plane > 0;
		if (ghost)
		{
			for (int p = 0; p < plane; p++)
			{
				drawTerrainPlane(g, model, p, tileSize, opt, false, false);
				if (opt.showObjects)
				{
					drawObjectsOfPlane(g, model, p, tileSize, opt);
				}
			}
			g.setColor(new Color(0, 0, 0, GHOST_VEIL_ALPHA));
			g.fillRect(0, 0, size, size);
		}

		// Current plane terrain. In ghost mode, empty tiles stay transparent so the
		// dimmed floor below shows through.
		drawTerrainPlane(g, model, plane, tileSize, opt, ghost, opt.showFlags);

		// Height relief tint.
		if (opt.showHeightTint)
		{
			net.runelite.cache.region.Region r =
				new net.runelite.cache.region.Region(model.getRegionId());
			r.loadTerrain(model.getMap());
			int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
			int[][] hh = new int[X][Y];
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					hh[x][y] = -r.getTileHeight(plane, x, y); // bigger = higher
					min = Math.min(min, hh[x][y]);
					max = Math.max(max, hh[x][y]);
				}
			}
			int range = Math.max(1, max - min);
			for (int x = 0; x < X; x++)
			{
				for (int y = 0; y < Y; y++)
				{
					float t = (hh[x][y] - min) / (float) range;
					g.setColor(new Color((int) (t * 255), 40, (int) ((1 - t) * 255), 110));
					g.fillRect(x * tileSize, (Y - 1 - y) * tileSize, tileSize, tileSize);
				}
			}
		}

		// Grid.
		if (opt.showGrid && tileSize >= 5)
		{
			g.setColor(GRID);
			g.setStroke(new BasicStroke(1f));
			for (int i = 0; i <= X; i++)
			{
				g.drawLine(i * tileSize, 0, i * tileSize, size);
				g.drawLine(0, i * tileSize, size, i * tileSize);
			}
		}

		// Objects on the current plane (drawn bright, on top of the grid).
		if (opt.showObjects)
		{
			if (opt.allPlanes)
			{
				for (int p = 0; p <= plane; p++)
				{
					drawObjectsOfPlane(g, model, p, tileSize, opt);
				}
			}
			else
			{
				drawObjectsOfPlane(g, model, plane, tileSize, opt);
			}
		}

		// Object map_scene icons (bank/altar/shop/etc — the common minimap icons), if enabled.
		if (opt.showObjectMapIcons)
		{
			drawObjectMapIcons(g, model, plane, tileSize, opt);
		}

		// Fullscreen World Map compositemap icons (dungeon/transport/special markers), if enabled.
		if (opt.showWorldMapIcons)
		{
			drawWorldMapIcons(g, model, plane, tileSize);
		}

		// Server-spawned objects (ClientObj.java) + their map_icon markers, if enabled.
		if (opt.showServerSpawns)
		{
			drawServerSpawns(g, model, plane, tileSize);
		}

		g.dispose();
		return image;
	}

	/**
	 * Stamps the {@code map_icon} icon on every placed object that carries one — the bank / altar /
	 * well / portal / minigame icons the client draws on the minimap. An object's {@code mapAreaId}
	 * resolves (via its area definition's spriteId) to the icon sprite. Only shows objects actually
	 * in the cache — runtime server spawns are handled separately by {@link #drawServerSpawns}.
	 */
	private void drawObjectMapIcons(Graphics2D g, RegionModel model, int plane, int tileSize, Options opt)
	{
		Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (Location loc : model.getLocations().getLocations())
		{
			if (loc.getPosition().getZ() != plane)
			{
				continue;
			}
			ObjectDefinition d = service.getObject(loc.getId());
			if (d == null || d.getMapAreaId() < 0)
			{
				continue;
			}
			java.awt.image.BufferedImage icon = service.getWorldMapIcon(d.getMapAreaId());
			if (icon == null)
			{
				continue;
			}
			int px = loc.getPosition().getX() * tileSize;
			int py = (Y - 1 - loc.getPosition().getY()) * tileSize;
			int isz = Math.max(12, (int) (tileSize * 1.4));
			g.drawImage(icon, px + (tileSize - isz) / 2, py + (tileSize - isz) / 2, isz, isz, null);
		}
		if (prevInterp != null) { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp); }
	}

	/**
	 * Overlays the server-spawned objects parsed from ClientObj.java (bank chests, altars, benches,
	 * minimap-icon markers) that fall in this region/plane. Each is drawn as a normal object marker
	 * (so you can identify it) with a purple corner tag marking it as a runtime server spawn, plus
	 * its {@code map_icon} sprite when it has one — making the objects the server injects (which are
	 * NOT in the cache) visible in the editor at their real positions.
	 */
	private void drawServerSpawns(Graphics2D g, RegionModel model, int plane, int tileSize)
	{
		int regionId = model.getRegionId();
		int baseX = (regionId >> 8) * X;
		int baseY = (regionId & 0xFF) * Y;
		Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int[] s : service.getServerSpawns())
		{
			int id = s[0], x = s[1], y = s[2], z = s[3], type = s[4], rot = s[5];
			if (z != plane)
			{
				continue;
			}
			int lx = x - baseX, ly = y - baseY;
			if (lx < 0 || lx >= X || ly < 0 || ly >= Y)
			{
				continue;
			}
			int px = lx * tileSize;
			int py = (Y - 1 - ly) * tileSize;
			// Render the ClientObj object exactly like a real placed object (bench, bank, wall,
			// scenery diamond …) so you can identify what the server puts here.
			drawObject(g, new Location(id, type, rot, new Position(lx, ly, z)), tileSize);
			// A small purple corner tag marks it as a server (ClientObj.java) spawn, not cache data.
			g.setColor(new Color(180, 90, 255, 235));
			int tag = Math.max(3, tileSize / 4);
			g.fillRect(px, py, tag, tag);
			ObjectDefinition def = service.getObject(id);
			if (def != null && def.getMapAreaId() >= 0)
			{
				java.awt.image.BufferedImage icon = service.getWorldMapIcon(def.getMapAreaId());
				if (icon != null)
				{
					int isz = Math.max(14, (int) (tileSize * 1.5));
					g.drawImage(icon, px + (tileSize - isz) / 2, py + (tileSize - isz) / 2, isz, isz, null);
				}
			}
		}
		if (prevInterp != null) { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp); }
	}

	/**
	 * Draws the fullscreen World Map element icons whose world position falls inside this region
	 * and on the current plane. Each element is {areaDefinitionId, worldPos}; the icon sprite is
	 * resolved from the area definition's spriteId (see {@link MapEditorService#getWorldMapIcon}).
	 */
	private void drawWorldMapIcons(Graphics2D g, RegionModel model, int plane, int tileSize)
	{
		int regionId = model.getRegionId();
		int baseX = (regionId >> 8) * X;
		int baseY = (regionId & 0xFF) * Y;
		Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (net.runelite.cache.definitions.WorldMapElementDefinition el : service.getWorldMapElements())
		{
			Position p = el.getWorldPosition();
			if (p == null || p.getZ() != plane)
			{
				continue;
			}
			int lx = p.getX() - baseX;
			int ly = p.getY() - baseY;
			if (lx < 0 || lx >= X || ly < 0 || ly >= Y)
			{
				continue;
			}
			java.awt.image.BufferedImage icon = service.getWorldMapIcon(el.getAreaDefinitionId());
			if (icon == null)
			{
				continue;
			}
			int px = lx * tileSize;
			int py = (Y - 1 - ly) * tileSize;
			int isz = Math.max(14, (int) (tileSize * 1.5));
			int ix = px + (tileSize - isz) / 2;
			int iy = py + (tileSize - isz) / 2;
			g.drawImage(icon, ix, iy, isz, isz, null);
		}
		if (prevInterp != null) { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp); }
	}

	/** Draws every terrain tile of one plane. */
	private void drawTerrainPlane(Graphics2D g, RegionModel model, int plane, int tileSize,
		Options opt, boolean transparentEmpty, boolean drawFlags)
	{
		for (int x = 0; x < X; x++)
		{
			for (int y = 0; y < Y; y++)
			{
				Tile tile = model.getTile(plane, x, y);
				int px = x * tileSize;
				int py = (Y - 1 - y) * tileSize;
				drawTerrainTile(g, tile, px, py, tileSize, opt, transparentEmpty);

				// White outline on the OUTSIDE boundary of overlay regions only (matching the
				// official editor): draw a tile-border edge only where the neighbour has no overlay,
				// and always draw the interior diagonal cut (overlay-vs-underlay within one tile).
				if (opt.showOverlay && (tile.overlayId & 0xFFFF) != 0)
				{
					drawOverlayBorder(g, model, plane, x, y, px, py, tileSize, tile);
				}

				if (drawFlags && (tile.settings & 0xFF) != 0)
				{
					int s = tile.settings & 0xFF;
					// Colour-code by flag bit so different flags read at a glance (bits blend
					// when a tile has several): blocked=red, bridge=blue, flag4=green, flag8=orange.
					if ((s & 0x1) != 0) { g.setColor(new Color(220, 40, 40, 115)); g.fillRect(px, py, tileSize, tileSize); }
					if ((s & 0x2) != 0) { g.setColor(new Color(40, 120, 235, 115)); g.fillRect(px, py, tileSize, tileSize); }
					if ((s & 0x4) != 0) { g.setColor(new Color(40, 200, 90, 105)); g.fillRect(px, py, tileSize, tileSize); }
					if ((s & 0x8) != 0) { g.setColor(new Color(235, 180, 40, 105)); g.fillRect(px, py, tileSize, tileSize); }
					if ((s & 0xF0) != 0) { g.setColor(new Color(200, 60, 220, 105)); g.fillRect(px, py, tileSize, tileSize); }
					// The numeric flag value, when tiles are big enough to read.
					if (tileSize >= 14)
					{
						String lbl = String.valueOf(s);
						g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, Math.min(tileSize - 4, 12f)));
						java.awt.FontMetrics fm = g.getFontMetrics();
						int tx = px + (tileSize - fm.stringWidth(lbl)) / 2;
						int ty = py + (tileSize + fm.getAscent()) / 2 - 1;
						g.setColor(new Color(0, 0, 0, 170));
						g.drawString(lbl, tx + 1, ty + 1);
						g.setColor(Color.WHITE);
						g.drawString(lbl, tx, ty);
					}
				}
			}
		}
	}

	/** Draws the object markers for exactly one plane, honouring show toggles. */
	private void drawObjectsOfPlane(Graphics2D g, RegionModel model, int plane, int tileSize, Options opt)
	{
		for (Location loc : model.getLocations().getLocations())
		{
			if (loc.getPosition().getZ() != plane)
			{
				continue;
			}
			// Per-category Show/Hide (Wall / Door / Wall decor / Scenery / Roof / Ground decor / Icon).
			if (!catVisible(opt, loc.getType(), service.getObject(loc.getId())))
			{
				continue;
			}
			drawObject(g, loc, tileSize);
		}
	}

	// ---- terrain tile with real overlay shapes --------------------------

	private void drawTerrainTile(Graphics2D g, Tile tile, int px, int py, int tileSize, Options opt,
		boolean transparentEmpty)
	{
		int under = underlayRgb(tile);
		int over = opt.showOverlay ? overlayRgb(tile) : -1;

		// In onion-skin mode an empty tile on the current plane is left transparent
		// so the dimmed floor below shows through.
		if (transparentEmpty && under < 0 && over < 0)
		{
			return;
		}

		Color underC = under >= 0 ? new Color(under) : VOID;

		if (over < 0)
		{
			g.setColor(underC);
			g.fillRect(px, py, tileSize, tileSize);
			return;
		}

		int path = tile.overlayPath & 0xFF;
		if (path == 0)
		{
			// full-tile overlay (outline drawn region-aware in drawTerrainPlane)
			g.setColor(new Color(over));
			g.fillRect(px, py, tileSize, tileSize);
			return;
		}

		double[][] poly = shapePolygon(path, tile.overlayRotation & 3, true);
		if (poly == null)
		{
			g.setColor(new Color(over));
			g.fillRect(px, py, tileSize, tileSize);
			return;
		}

		// underlay beneath, exact overlay polygon on top (clean diagonal cut)
		g.setColor(underC);
		g.fillRect(px, py, tileSize, tileSize);
		g.setColor(new Color(over));
		java.awt.geom.Path2D.Double p2 = new java.awt.geom.Path2D.Double();
		p2.moveTo(px + poly[0][0] * tileSize, py + poly[0][1] * tileSize);
		for (int i = 1; i < poly.length; i++)
		{
			p2.lineTo(px + poly[i][0] * tileSize, py + poly[i][1] * tileSize);
		}
		p2.closePath();
		g.fill(p2);
		// Overlay outline (region edges + the diagonal cut) is drawn in drawTerrainPlane so it can
		// be neighbour-aware — no white lines between two adjacent overlay tiles.
	}

	/** True if the tile at (x,y) on this plane carries any overlay (for region-edge outlining). */
	private boolean tileHasOverlay(RegionModel model, int plane, int x, int y)
	{
		if (x < 0 || y < 0 || x >= X || y >= Y) { return false; }
		Tile t = model.getTile(plane, x, y);
		return t != null && (t.overlayId & 0xFFFF) != 0;
	}

	private static final double[][] FULL_SQUARE = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

	/**
	 * Draws the white overlay outline for one tile: each edge of the overlay polygon (a full square
	 * for path 0, else the shape polygon) is drawn only if it's the OUTER boundary of the overlay
	 * region. A tile-border edge is drawn only where the neighbour across it has no overlay; the
	 * interior diagonal cut (overlay meeting underlay inside the tile) is always drawn. Result:
	 * white only around the outside of overlay regions, never between two adjacent overlay tiles.
	 */
	private void drawOverlayBorder(Graphics2D g, RegionModel model, int plane, int x, int y,
		int px, int py, int tileSize, Tile tile)
	{
		double[][] poly;
		if ((tile.overlayPath & 0xFF) == 0)
		{
			poly = FULL_SQUARE;
		}
		else
		{
			poly = shapePolygon(tile.overlayPath & 0xFF, tile.overlayRotation & 3, true);
			if (poly == null) { poly = FULL_SQUARE; }
		}
		Object prevAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(OVERLAY_OUTLINE);
		g.setStroke(new BasicStroke(1f));
		final double eps = 1e-4;
		int n = poly.length;
		for (int i = 0; i < n; i++)
		{
			double ax = poly[i][0], ay = poly[i][1];
			double bx = poly[(i + 1) % n][0], by = poly[(i + 1) % n][1];
			boolean draw;
			if (ax < eps && bx < eps)                       { draw = !tileHasOverlay(model, plane, x - 1, y); }        // west (screen-left)
			else if (ax > 1 - eps && bx > 1 - eps)          { draw = !tileHasOverlay(model, plane, x + 1, y); }        // east
			else if (ay < eps && by < eps)                  { draw = !tileHasOverlay(model, plane, x, y + 1); }        // north (screen-top)
			else if (ay > 1 - eps && by > 1 - eps)          { draw = !tileHasOverlay(model, plane, x, y - 1); }        // south
			else                                            { draw = true; }                                          // interior cut edge
			if (draw)
			{
				g.drawLine(px + (int) Math.round(ax * tileSize), py + (int) Math.round(ay * tileSize),
					px + (int) Math.round(bx * tileSize), py + (int) Math.round(by * tileSize));
			}
		}
		if (prevAa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAa); }
	}

	private int underlayRgb(Tile tile)
	{
		int underlayId = tile.underlayId & 0xFFFF;
		if (underlayId == 0)
		{
			return -1;
		}
		UnderlayDefinition u = service.getUnderlay(underlayId - 1); // stored id is definition+1
		return u != null ? (u.getColor() & 0xFFFFFF) : -1;
	}

	private int overlayRgb(Tile tile)
	{
		int overlayId = tile.overlayId & 0xFFFF;
		if (overlayId == 0)
		{
			return -1;
		}
		OverlayDefinition o = service.getOverlay(overlayId - 1); // stored id is definition+1
		if (o == null)
		{
			return -1;
		}
		// Magenta (0xFF00FF) = OSRS "hidden/transparent overlay" — show the underlay, not pink.
		if ((o.getRgbColor() & 0xFFFFFF) == 0xFF00FF)
		{
			return -1;
		}
		int rgb = o.getRgbColor();
		if (rgb == 0 && o.getSecondaryRgbColor() != -1)
		{
			rgb = o.getSecondaryRgbColor();
		}
		if (rgb == 0 && o.getTexture() != -1)
		{
			rgb = service.hasTextures() ? service.getTextureAverage(o.getTexture()) : 0x5A5A5A;
		}
		return rgb & 0xFFFFFF;
	}

	// ---- shape masks (ported from MapImageDumper, parameterised scale) --

	/**
	 * Mask for an overlay path (1..11) + stored rotation, or null for full tile.
	 * Mapping per the client: shape = path, rotation/shape converted, masks
	 * indexed by converted shape 1..8.
	 */
	public static byte[] maskFor(int path, int rotation)
	{
		ensureShapes();
		if (path < 1 || path > 11)
		{
			return null;
		}
		int rot = convertTileRotation(rotation & 3, path);
		int shape = convertTileShape(path);
		return TILE_SHAPES[shape - 1][rot];
	}

	// ---- exact polygon shapes -------------------------------------------
	//
	// Every OSRS overlay shape is the unit tile cut by ONE straight line, so the
	// overlay part is the square clipped by a half-plane a*x + b*y <= c (x
	// left→right, y top→bottom / from the NORTH edge) and the underlay part is
	// the complement. This gives clean diagonal cuts like the official editor.

	/** {a,b,c} per converted shape 1..8 x rotation: keep where a*x + b*y <= c. */
	private static final double[][][] HALF =
	{
		// shape 1: diagonal halves
		{{1, -1, 0}, {1, 1, 1}, {-1, 1, 0}, {-1, -1, -1}},
		// shape 2: shallow small corner
		{{2, 1, 1}, {-1, 2, 0}, {-2, -1, -2}, {1, -2, -1}},
		// shape 3: mirrored shallow corner
		{{-2, 1, -1}, {-1, -2, -2}, {2, -1, 0}, {1, 2, 1}},
		// shape 4: complement of 2
		{{-2, -1, -1}, {1, -2, 0}, {2, 1, 2}, {-1, 2, 1}},
		// shape 5: complement of 3
		{{2, -1, 1}, {1, 2, 2}, {-2, 1, 0}, {-1, -2, -1}},
		// shape 6: straight halves
		{{1, 0, 0.5}, {0, 1, 0.5}, {-1, 0, -0.5}, {0, -1, -0.5}},
		// shape 7: small diagonal corner
		{{1, -1, -0.5}, {1, 1, 0.5}, {-1, 1, -0.5}, {-1, -1, -1.5}},
		// shape 8: complement of 7
		{{-1, 1, 0.5}, {-1, -1, -0.5}, {1, -1, 0.5}, {1, 1, 1.5}},
	};

	/**
	 * The overlay (or complement/underlay) polygon for a path 1..11 + rotation,
	 * as unit-square points (x right, y down from the north edge), wound
	 * clockwise in y-down coords. Null when the part is empty or path is full.
	 */
	public static double[][] shapePolygon(int path, int rotation, boolean overlayPart)
	{
		if (path < 1 || path > 11)
		{
			return null;
		}
		int rot = convertTileRotation(rotation & 3, path);
		int shape = convertTileShape(path);
		double[] hp = HALF[shape - 1][rot];
		// unit square, clockwise in y-down coordinates
		double[][] square = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		double a = hp[0], b = hp[1], c = hp[2];
		if (!overlayPart)
		{
			a = -a; b = -b; c = -c; // complement half-plane
		}
		return clip(square, a, b, c);
	}

	/** Sutherland–Hodgman clip of a polygon against a*x + b*y <= c. */
	private static double[][] clip(double[][] poly, double a, double b, double c)
	{
		java.util.List<double[]> out = new java.util.ArrayList<>();
		int n = poly.length;
		for (int i = 0; i < n; i++)
		{
			double[] p = poly[i];
			double[] q = poly[(i + 1) % n];
			double dp = a * p[0] + b * p[1] - c;
			double dq = a * q[0] + b * q[1] - c;
			boolean inP = dp <= 1e-9, inQ = dq <= 1e-9;
			if (inP)
			{
				out.add(p);
			}
			if (inP != inQ)
			{
				double t = dp / (dp - dq);
				out.add(new double[]{p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])});
			}
		}
		return out.size() < 3 ? null : out.toArray(new double[0][]);
	}

	private static int convertTileRotation(int rotation, int shape)
	{
		if (shape == 9)
		{
			rotation = rotation + 1 & 3;
		}
		if (shape == 10 || shape == 11)
		{
			rotation = rotation + 3 & 3;
		}
		return rotation;
	}

	private static int convertTileShape(int shape)
	{
		return shape != 9 && shape != 10 ? (shape == 11 ? 8 : shape) : 1;
	}

	private static synchronized void ensureShapes()
	{
		if (TILE_SHAPES != null)
		{
			return;
		}
		int s = SHAPE_SCALE;
		byte[][][] t = new byte[8][4][];

		// shape 1: diagonal halves
		t[0][0] = gen(s, (r, c) -> c <= r);
		t[0][1] = gen(s, (r, c) -> c <= (s - 1 - r));
		t[0][2] = gen(s, (r, c) -> c >= r);
		t[0][3] = gen(s, (r, c) -> c >= (s - 1 - r));
		// shape 2: shallow-slope small corner
		t[1][0] = gen(s, (r, c) -> c <= (s - 1 - r) >> 1);
		t[1][1] = gen(s, (r, c) -> c >= r << 1);
		t[1][2] = gen(s, (r, c) -> (s - 1 - c) <= r >> 1);
		t[1][3] = gen(s, (r, c) -> (s - 1 - c) >= (s - 1 - r) << 1);
		// shape 3: mirrored shallow corner
		t[2][0] = gen(s, (r, c) -> (s - 1 - c) <= (s - 1 - r) >> 1);
		t[2][1] = gen(s, (r, c) -> c >= (s - 1 - r) << 1);
		t[2][2] = gen(s, (r, c) -> c <= r >> 1);
		t[2][3] = gen(s, (r, c) -> (s - 1 - c) >= r << 1);
		// shape 4: large shallow (complement-ish)
		t[3][0] = gen(s, (r, c) -> c >= (s - 1 - r) >> 1);
		t[3][1] = gen(s, (r, c) -> c <= r << 1);
		t[3][2] = gen(s, (r, c) -> (s - 1 - c) >= r >> 1);
		t[3][3] = gen(s, (r, c) -> (s - 1 - c) <= (s - 1 - r) << 1);
		// shape 5
		t[4][0] = gen(s, (r, c) -> (s - 1 - c) >= (s - 1 - r) >> 1);
		t[4][1] = gen(s, (r, c) -> c <= (s - 1 - r) << 1);
		t[4][2] = gen(s, (r, c) -> c >= r >> 1);
		t[4][3] = gen(s, (r, c) -> (s - 1 - c) <= r << 1);
		// shape 6: straight halves
		t[5][0] = gen(s, (r, c) -> c <= s / 2);
		t[5][1] = gen(s, (r, c) -> r <= s / 2);
		t[5][2] = gen(s, (r, c) -> c >= s / 2);
		t[5][3] = gen(s, (r, c) -> r >= s / 2);
		// shape 7: small diagonal corner
		t[6][0] = gen(s, (r, c) -> c <= r - s / 2);
		t[6][1] = gen(s, (r, c) -> c <= (s - 1 - r) - s / 2);
		t[6][2] = gen(s, (r, c) -> (s - 1 - c) <= (s - 1 - r) - s / 2);
		t[6][3] = gen(s, (r, c) -> (s - 1 - c) <= r - s / 2);
		// shape 8: large diagonal (complement of 7)
		t[7][0] = gen(s, (r, c) -> c >= r - s / 2);
		t[7][1] = gen(s, (r, c) -> c >= (s - 1 - r) - s / 2);
		t[7][2] = gen(s, (r, c) -> (s - 1 - c) >= (s - 1 - r) - s / 2);
		t[7][3] = gen(s, (r, c) -> (s - 1 - c) >= r - s / 2);

		TILE_SHAPES = t;
	}

	private interface MaskFn
	{
		boolean set(int row, int col);
	}

	private static byte[] gen(int s, MaskFn fn)
	{
		byte[] m = new byte[s * s];
		int i = 0;
		for (int r = 0; r < s; r++)
		{
			for (int c = 0; c < s; c++)
			{
				if (fn.set(r, c))
				{
					m[i] = -1;
				}
				i++;
			}
		}
		return m;
	}

	// ---- objects ---------------------------------------------------------

	private void drawObject(Graphics2D g, Location loc, int tileSize)
	{
		int x = loc.getPosition().getX();
		int y = loc.getPosition().getY();
		int px = x * tileSize;
		int py = (Y - 1 - y) * tileSize;   // north edge (top)
		int type = loc.getType();
		int o = loc.getOrientation() & 3;

		// screen edges of this tile
		int L = px, R = px + tileSize, T = py, B = py + tileSize;

		ObjectDefinition def = service.getObject(loc.getId());

		// A wall placed with a scenery type (10/11) still looks like a wall — the
		// object itself is a wall (by shape or "wall/fence/gate" name), so draw it
		// as a wall line rather than a scenery diamond.
		boolean wallLike = (type == 10 || type == 11) && isWallLike(def);
		if (wallLike)
		{
			type = 0; // render as a straight wall on the oriented edge
		}

		if (type >= 0 && type <= 3 || type == 9)
		{
			// Walls, colour-coded by type so they're easy to read: straight=yellow,
			// corner=blue (little triangle), diagonal=green, square/pillar=white.
			Object prevAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g.setStroke(new BasicStroke(Math.max(1f, tileSize / 8f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
			switch (type)
			{
				case 0: // straight wall on the oriented edge — yellow
					g.setColor(WALL_STRAIGHT);
					edge(g, o, L, T, R, B);
					break;
				case 1: // corner + L-corner — a little filled triangle hugging the corner, blue
				case 2:
				{
					g.setColor(WALL_CORNER);
					int s = Math.max(3, tileSize / 3); // small: only ~1/3 of the tile
					int[] xs, ys;
					switch (o)
					{
						case 0:  xs = new int[]{L, L + s, L}; ys = new int[]{T, T, T + s}; break; // NW
						case 1:  xs = new int[]{R, R - s, R}; ys = new int[]{T, T, T + s}; break; // NE
						case 2:  xs = new int[]{R, R - s, R}; ys = new int[]{B, B, B - s}; break; // SE
						default: xs = new int[]{L, L + s, L}; ys = new int[]{B, B, B - s};        // SW
					}
					g.fillPolygon(xs, ys, 3);
					break;
				}
				case 3: // pillar / square corner — a small white block at the corner
				{
					g.setColor(WALL_SQUARE);
					int s = Math.max(2, tileSize / 3);
					int cxp = (o == 1 || o == 2) ? R - s : L;
					int cyp = (o == 2 || o == 3) ? B - s : T;
					g.fillRect(cxp, cyp, s, s);
					break;
				}
				default: // 9 diagonal wall — green. Match the OSRS world map (MapImageDumper):
					// rotation 1/3 = "\" (NW-SE), rotation 0/2 = "/" (SW-NE).
					g.setColor(WALL_DIAGONAL);
					if (o == 1 || o == 3) { g.drawLine(L, T, R, B); }
					else { g.drawLine(L, B, R, T); }
			}
			if (prevAa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAa); }
		}
		else if (type >= 4 && type <= 8)
		{
			// Wall decoration: small tick on the oriented edge (cyan category colour).
			g.setColor(CAT_WALLDECOR);
			g.setStroke(new BasicStroke(Math.max(1.5f, tileSize / 8f)));
			int cx = px + tileSize / 2, cy = py + tileSize / 2, r = tileSize / 3;
			switch (o)
			{
				case 0: g.drawLine(L, cy, L + r, cy); break;
				case 1: g.drawLine(cx, T, cx, T + r); break;
				case 2: g.drawLine(R, cy, R - r, cy); break;
				default: g.drawLine(cx, B, cx, B - r);
			}
		}
		else if (type == 22)
		{
			// Ground decoration (no map_scene): a small blue dot, no orientation stem.
			g.setColor(GROUND_DECOR);
			int d = Math.max(2, tileSize / 3);
			g.fillOval(px + (tileSize - d) / 2, py + (tileSize - d) / 2, d, d);
		}
		else
		{
			// Scenery / roofs: a small centred diamond, no orientation stem — category-coloured
			// (scenery = orange, roofs = purple) like the official editor's compact markers.
			int cx = px + tileSize / 2, cy = py + tileSize / 2;
			int d = Math.max(2, tileSize / 3);
			int[] xs = {cx, cx + d, cx, cx - d};
			int[] ys = {cy - d, cy, cy + d, cy};
			boolean roof = (type >= 12 && type <= 21);
			g.setColor(roof ? CAT_ROOF_FILL : CAT_SCENERY_FILL);
			g.fillPolygon(xs, ys, 4);
			g.setColor(roof ? CAT_ROOF_EDGE : CAT_SCENERY_EDGE);
			g.drawPolygon(xs, ys, 4);
		}
	}

	/**
	 * The display category of a placed object (by placement type + def), used for per-category
	 * Show/Hide toggles across the 2D map, 3D scene and click-through. Categories match the Objects
	 * tab's OBJ_CATEGORIES names: Wall / Door / Wall decoration / Scenery / Roof / Ground decor / Icon.
	 */
	public static String locCategory(int type, ObjectDefinition def)
	{
		if (def != null && def.getMapAreaId() >= 0) { return "Icon"; }
		boolean wallLike = (type == 10 || type == 11) && isWallLike(def);
		if (type == 22) { return "Ground decor"; }
		if (type >= 4 && type <= 8) { return "Wall decoration"; }
		if (type <= 3 || type == 9 || wallLike)
		{
			String n = def != null && def.getName() != null ? def.getName().toLowerCase() : "";
			return (n.contains("door") || n.contains("gate")) ? "Door" : "Wall";
		}
		if (type >= 12 && type <= 21) { return "Roof"; }
		return "Scenery"; // 10-11 (and anything else)
	}

	/** Whether an object of this type/def should be shown, per the Options category toggles. */
	public static boolean catVisible(Options opt, int type, ObjectDefinition def)
	{
		switch (locCategory(type, def))
		{
			case "Wall":            return opt.showWalls;
			case "Door":            return opt.showDoors;
			case "Wall decoration": return opt.showWallDecor;
			case "Roof":            return opt.showRoofs;
			case "Ground decor":    return opt.showGroundDecor;
			case "Icon":            return opt.showIcons;
			default:                return opt.showScenery;
		}
	}

	/** Whether an object is a wall/fence/gate (by placement shape or name). */
	public static boolean isWallLike(ObjectDefinition def)
	{
		if (def == null)
		{
			return false;
		}
		int[] types = def.getObjectTypes();
		if (types != null)
		{
			for (int t : types)
			{
				if (t <= 3 || t == 9)
				{
					return true;
				}
			}
		}
		String n = def.getName();
		if (n != null)
		{
			n = n.toLowerCase();
			return n.contains("wall") || n.contains("fence") || n.contains("gate")
				|| n.contains("hedge") || n.contains("railing") || n.contains("door") || n.contains("balustrade");
		}
		return false;
	}

	/** The natural placement location-type for an object (0 for walls, else 10). */
	public static int naturalType(ObjectDefinition def)
	{
		if (def == null)
		{
			return 10;
		}
		int[] types = def.getObjectTypes();
		if (types != null && types.length > 0)
		{
			return types[0];
		}
		return isWallLike(def) ? 0 : 10;
	}

	/** Draw the tile edge for orientation (0=W,1=N,2=E,3=S). */
	private void edge(Graphics2D g, int o, int L, int T, int R, int B)
	{
		switch (o & 3)
		{
			case 0: g.drawLine(L, T, L, B); break; // west
			case 1: g.drawLine(L, T, R, T); break; // north
			case 2: g.drawLine(R, T, R, B); break; // east
			default: g.drawLine(L, B, R, B);       // south
		}
	}

	/** A short tick from the tile centre toward the facing edge (0=W,1=N,2=E,3=S). */
	private void drawFacing(Graphics2D g, int px, int py, int tileSize, int orientation, Color c)
	{
		int cx = px + tileSize / 2, cy = py + tileSize / 2;
		int r = tileSize / 2;
		int ex = cx, ey = cy;
		switch (orientation & 3)
		{
			case 0: ex = cx - r; break; // west
			case 1: ey = cy - r; break; // north
			case 2: ex = cx + r; break; // east
			default: ey = cy + r;       // south
		}
		g.setColor(c);
		g.setStroke(new BasicStroke(Math.max(1.5f, tileSize / 8f)));
		g.drawLine(cx, cy, ex, ey);
	}
}
