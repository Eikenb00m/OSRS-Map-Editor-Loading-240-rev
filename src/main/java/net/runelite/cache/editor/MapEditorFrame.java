/*
 * Swing front-end for the map editor.
 *
 *  - Center:  a zoomable/scrollable canvas showing one plane of the region.
 *  - Right:   tool palette + parameters + selected-tile inspector.
 *  - Toolbar: open cache, go-to region, add region, save, reload, plane, zoom,
 *             and layer toggles.
 *
 * All cache access goes through MapEditorService; all edits mutate the loaded
 * RegionModel and re-render.
 */
package net.runelite.cache.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import net.runelite.cache.definitions.MapDefinition.Tile;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.region.Location;

public class MapEditorFrame extends JFrame
{
	private enum Tool { SELECT, UNDERLAY, OVERLAY, HEIGHT, SETTINGS, PLACE_OBJECT, DELETE_OBJECT, PLACE_NPC }

	private final MapEditorService service;

	private RegionModel region;
	private int plane = 0;
	private int tileSize = 12;
	private Tool tool = Tool.SELECT;

	private int selX = -1, selY = -1;

	private final MapRenderer renderer;
	private final MapRenderer.Options renderOptions = new MapRenderer.Options();
	private BufferedImage image2D;
	private BufferedImage image3D;

	// "Neighbours" preview: a dimmed 5-tile-wide strip of the adjacent regions on all 4
	// sides in the 2D and 3D views, so region edges can be lined up.
	private boolean showNeighbors;
	private final RegionModel[] neighborModels2D = new RegionModel[8]; // [N,S,E,W,NE,NW,SE,SW]
	private String neighborSig2D;

	// 3D view — render size follows the viewport so it fills the window
	private int view3dW = 960, view3dH = 720;
	private boolean threeD = false;
	private boolean showSpawns = false;
	private boolean showSpawnNames = true; // draw NPC/object names next to spawn markers
	private boolean showCompass = true;    // N/S/E/W compass overlay in the 3D view corner
	private boolean showHeights = false;   // per-tile height numbers overlaid on the 3D view
	private boolean showBuildCheck = false; // red overlay on non-flat tiles (bad for buildings)
	private boolean showConflicts = false;  // red overlay on tiles with stacked/conflicting walls
	private final SceneBuilder sceneBuilder;
	private Renderer3D renderer3D = new Renderer3D(view3dW, view3dH, 0x202830);
	// "Fast" renderer used while actively moving/orbiting/animating: renders at a fraction of the
	// resolution (pixel work scales with the SQUARE of this) then the canvas scales it up, so motion
	// stays smooth. The full-res renderer snaps back the instant you stop, so still frames are crisp.
	// Tune FAST_SCALE for the sharpness/smoothness trade-off (0.5 = fastest/softest, 0.8 = sharper).
	private static final double FAST_SCALE = 0.6;
	private Renderer3D renderer3DFast = new Renderer3D((int) (view3dW * FAST_SCALE), (int) (view3dH * FAST_SCALE), 0x202830);
	// Dedicated renderer for colour-id pick passes so they never overwrite the shared display buffer.
	private Renderer3D pickRenderer = new Renderer3D(view3dW, view3dH, 0x202830);
	// WASD/QE fly speed (world units per movement tick), adjustable via the Settings ribbon slider.
	private double moveStep = 55;
	// When true, movement/orbit renders at FULL resolution (crisp, no softening) instead of the
	// downscaled fast renderer — smoother on a powerful PC, heavier on a weak one. Toggled in Settings.
	private boolean sharpWhileMoving = false;
	private javax.swing.JScrollPane canvasScroll;
	private JFrame popout2DFrame;
	private JFrame popout3DFrame;
	private boolean splitMode;
	private boolean splitHorizontal = true; // like the official editor: 2D left, 3D right
	private javax.swing.JSplitPane splitPane;
	private java.awt.Component centerComp;
	private JPanel centerWrap; // holds the map (CENTER) + hotbar (SOUTH), so the east panel is full height
	private boolean manual2DZoom;
	private boolean panning2D;
	private java.awt.Point panStartScreen;
	private java.awt.Point panStartView;
	private double[][] selCorners3D; // selected tile's 4 world corners, for the 3D highlight
	private int[][] planeGridHeights; // [65][65] current-plane corner heights for the 3D grid
	private Throwable lastLoadError; // last region-load failure, for diagnosis

	// interactive height sculpting (blank field = click raises / right-click lowers by step)
	private final javax.swing.JSlider heightSlider = new javax.swing.JSlider(1, 64, 8);
	private int heightStep = 8;
	private boolean paintLower;
	private boolean paintAbsolute;
	private net.runelite.cache.region.Region strokeHeights;

	// undo/redo (snapshots of tiles + locations)
	private static final int UNDO_LIMIT = 50;
	private final java.util.ArrayDeque<Snapshot> undoStack = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Snapshot> redoStack = new java.util.ArrayDeque<>();

	// minimap + panels that can be shown/hidden from the View menu
	private JComponent minimap;
	private BufferedImage minimapImage;
	private JComponent toolColumn;
	private JComponent hotbar;
	private java.awt.Component eastPanel;
	private boolean viewAllPlanes; // merge all 4 planes into the view

	// same-id flash for the selected object in 2D
	private javax.swing.Timer pulseTimer;
	private boolean pulseOn;

	// shaped-overlay paint can set the tile's underlay at the same time
	private boolean paintUnderlayToo;

	// NPC placement (writes server spawn json on save)
	private int placeNpcId = -1;
	private String placeNpcName = "";
	private int placeNpcDir; // 0=S 1=W 2=N 3=E
	private JCheckBox spawnsBox;

	// side panel tabs, selected via the top ribbon
	private javax.swing.JTabbedPane sideTabs;
	private boolean needDividerReset;

	private static final class Snapshot
	{
		final Tile[][][] tiles;
		final java.util.List<Location> locs;

		Snapshot(Tile[][][] tiles, java.util.List<Location> locs)
		{
			this.tiles = tiles;
			this.locs = locs;
		}
	}
	private Renderer3D.Scene scene3D;
	private boolean sceneDirty = true;
	private final Renderer3D.Camera camera = new Renderer3D.Camera();
	private int lastDragX, lastDragY;
	private boolean dragged3D;
	private boolean orbiting;
	private Location selectedLoc;
	private Location movingLoc;
	private SpawnLoader.Spawn selectedNpc;    // selected NPC spawn (2D/3D)
	private Location drag2DLoc;               // object being dragged in 2D
	private Location drag3DLoc;               // object being dragged in 3D
	private boolean drag3DMoved;              // whether the 3D drag actually moved (for undo)
	private int[] lastMoveTile;               // last tile a 3D drag placed on (throttles rebuilds)
	private SpawnLoader.Spawn drag2DNpc;      // NPC being dragged in 2D

	// 2D click-select filter: which stacked location a click grabs. 0=all, 1=objects (non-ground),
	// 2=ground decoration, 3=walls. Clicking the same tile repeatedly cycles through the matches.
	private int selFilter = 0;
	private javax.swing.JComboBox<String> selFilterCombo;
	private int cycleTileX = -1, cycleTileY = -1, cycleIdx = 0, lastMatchCount = 0;

	// Marquee multi-select + clipboard for copy/paste of objects (2D).
	private final java.util.LinkedHashSet<Location> selectedLocs = new java.util.LinkedHashSet<>();
	private boolean marqueeSelecting;
	private int marqStartX, marqStartY, marqCurX, marqCurY; // tile coords
	private java.util.List<int[]> clipboard;   // each entry: {id, type, orientation, dx, dy}
	private int hoverX = -1, hoverY;           // last hovered tile, for paste target
	// Terrain "stamp": a copied patch of tiles (underlay/overlay/shape) you can paste + rotate,
	// so composite shapes (roads, curves) are reusable. Each entry: {dx, dy, underlay, overlay, path, rot}.
	private java.util.List<int[]> tileStamp;
	private int tileStampW, tileStampH;
	private boolean pasteTilesMode;            // next left-click stamps the patch at that tile
	private javax.swing.JToggleButton tilePasteToggle; // ribbon toggle mirroring pasteTilesMode
	private final java.util.List<Location> objPickList = new java.util.ArrayList<>();
	private final java.util.Set<Integer> keysDown = new java.util.HashSet<>();
	private javax.swing.Timer moveTimer;
	private boolean animating;
	private int animTick;
	private javax.swing.Timer animTimer;
	private int animBaseSize;               // triangle count of the static part (scene3D truncate point)
	private long animStartMs;               // wall-clock start, so playback speed is time-based
	private static final int ANIM_FRAME_MS = 30; // ms per animation frame-unit (playback speed)
	private final Renderer3D.TextureSource texSource = new Renderer3D.TextureSource()
	{
		public int[] pixels(int id) { return service.getTexturePixels(id); }
		public int size() { return MapEditorService.TEX_SIZE; }
	};

	private final MapCanvas canvas2D = new MapCanvas(false);
	private final MapCanvas canvas3D = new MapCanvas(true);
	private final JLabel status = new JLabel(" ");

	// tool parameter fields
	private final JTextField underlayField = new JTextField("0", 5);
	private final JTextField overlayField = new JTextField("0", 5);
	private final JTextField overlayPathField = new JTextField("0", 3);
	private final JTextField overlayRotField = new JTextField("0", 3);
	private final JTextField heightField = new JTextField("", 5);
	private final JTextField settingsField = new JTextField("1", 5);
	private final JTextField objectIdField = new JTextField("0", 6);
	private final JTextField objectTypeField = new JTextField("10", 3);
	private final JTextField objectRotField = new JTextField("0", 3);
	private JButton placeRotBtn;               // the "Rot N/E/S/W" button, so keys can update its label
	private JPanel typeButtonRow;              // dynamic per-model buttons (rebuilt when an object is selected)
	private final java.util.List<JButton> typeButtonList = new java.util.ArrayList<>();

	private final JComboBox<Integer> planeCombo = new JComboBox<>(new Integer[]{0, 1, 2, 3});
	private final JPanel inspector = new JPanel();

	// brush + area selection + palette browsers
	private int brushSize = 0; // radius in tiles: 0 = single tile, 1 = 3x3, ...
	private final javax.swing.JSlider brushSlider = new javax.swing.JSlider(0, 20, 0);
	private boolean areaMode;
	private int areaX0 = -1, areaY0, areaX1, areaY1;
	private boolean areaDragging;
	private final java.util.Map<Tool, javax.swing.AbstractButton> toolRadios = new java.util.EnumMap<>(Tool.class);
	private final java.util.Map<Tool, javax.swing.AbstractButton> ribbonTools = new java.util.EnumMap<>(Tool.class);
	private final ButtonGroup ribbonToolGroup = new ButtonGroup();
	private JComponent underlayGrid, overlayGrid; // palette grids (repainted to move the selection border)
	private int pendingPick; // 0 none, 1 pick underlay, 2 pick overlay (armed by a tab's "Pick from map" button)
	private final Renderer3D previewRenderer = new Renderer3D(160, 160, 0x2d2d2d);
	private final JLabel objectPreview = new JLabel();
	private ObjEntry lastPreviewedObj;
	// Objects tab: small lazy row thumbnails + game-category (opcode 61) filter.
	private final Renderer3D thumbRenderer = new Renderer3D(34, 34, 0x2d2d2d);
	private final java.util.Map<Integer, javax.swing.Icon> objThumbCache = new java.util.HashMap<>();
	private String objGameCatFilter = ""; // exact game-category id to match, or blank for all
	private final javax.swing.DefaultListModel<ObjEntry> objectListModel = new javax.swing.DefaultListModel<>();
	private java.util.List<ObjEntry> allObjectEntries;

	// "Kit" tab: multi-TYPE objects (walls, roofs) shown as a scrollable grid of model thumbnails.
	private final JLabel kitPreview = new JLabel();
	private JButton kitRotBtn;
	private JPanel kitVariantGrid;          // wrap-layout grid of variant thumbnail tiles
	private JLabel kitVariantTitle;         // "N models" header
	private final java.util.List<JButton> kitTypeButtonList = new java.util.ArrayList<>();
	private final javax.swing.DefaultListModel<ObjEntry> kitListModel = new javax.swing.DefaultListModel<>();
	private java.util.List<ObjEntry> kitEntries;
	// Rendered variant thumbnails cached per object id (fixed rotation, so reusable).
	private final java.util.Map<Integer, java.util.List<javax.swing.ImageIcon>> kitThumbCache = new java.util.HashMap<>();

	// "Map Icons" tab: objects that carry a map_icon (mapAreaId) — the minimap-icon markers.
	private final javax.swing.DefaultListModel<ObjEntry> mapIconListModel = new javax.swing.DefaultListModel<>();
	private java.util.List<ObjEntry> mapIconEntries;
	private final java.util.Map<Integer, javax.swing.ImageIcon> mapIconThumbCache = new java.util.HashMap<>();

	private static final class ObjEntry
	{
		final int id;
		final String name;
		final String cat; // derived category: Wall / Door / Roof / Ground decor / … (for filtering)
		final int gameCat; // game category id (opcode 61 getCategory), or -1 if none
		ObjEntry(int id, String name) { this(id, name, "Object", -1); }
		ObjEntry(int id, String name, String cat) { this(id, name, cat, -1); }
		ObjEntry(int id, String name, String cat, int gameCat) { this.id = id; this.name = name; this.cat = cat; this.gameCat = gameCat; }
		public String toString() { return id + "  " + (name.isEmpty() ? "null" : name); }
	}

	/** Categories offered in the Objects tab filter (index 0 = "All" = no filter). */
	private static final String[] OBJ_CATEGORIES =
		{"All", "Wall", "Door", "Roof", "Ground decor", "Wall decoration", "Scenery", "Icon", "Object"};
	private String objectCatFilter = "All";

	/**
	 * Derives an object's category from its {@code objectTypes} (the OSRS location types it has
	 * models for): 0-3/9 = wall, 4-8 = wall decoration, 12-21 = roof, 22 = ground decoration, else
	 * scenery. Doors/gates are split out of walls by name; icon-markers by their map_icon/map_scene.
	 */
	private static String objectCategory(net.runelite.cache.definitions.ObjectDefinition def)
	{
		if (def == null)
		{
			return "Object";
		}
		// True minimap icons only (map_icon / mapAreaId): banks, altars, minigames. NOT map_scene
		// decorations (rocks/stairs/plants), which are ordinary scenery that also stamp a minimap
		// icon — categorising those as "Icon" was misleading. Matches MapRenderer.locCategory.
		if (def.getMapAreaId() >= 0)
		{
			return "Icon";
		}
		String n = def.getName() == null ? "" : def.getName().toLowerCase();
		boolean doorish = n.contains("door") || n.contains("gate");
		int[] types = def.getObjectTypes();
		if (types == null)
		{
			return doorish ? "Door" : "Scenery";
		}
		boolean roof = false, wallDeco = false, ground = false, wall = false, scenery = false;
		for (int t : types)
		{
			if (t >= 12 && t <= 21) { roof = true; }
			else if (t >= 4 && t <= 8) { wallDeco = true; }
			else if (t == 22) { ground = true; }
			else if (t <= 3 || t == 9) { wall = true; }
			else if (t == 10 || t == 11) { scenery = true; }
		}
		if (roof) { return "Roof"; }
		if (ground) { return "Ground decor"; }
		if (wallDeco) { return "Wall decoration"; }
		if (wall) { return doorish ? "Door" : "Wall"; }
		if (scenery) { return "Scenery"; }
		return "Object";
	}

	// ---- element hotbar (like the official editor's Elements panel) ----

	/** One assignable brush slot: a tool plus a snapshot of its parameters. */
	private static final class HotSlot
	{
		String tool;
		String underlay, overlay, path, rot, height, settings, objId, objType, objRot;
		String npcId, npcName, npcDir;
	}

	private static final String[] HOT_KEYS =
		{"1", "2", "3", "4", "5", "Q", "W", "E", "R", "T",
		 "A", "S", "D", "F", "G", "Z", "X", "C", "V", "B"};
	private static final File HOTBAR_FILE =
		new File(System.getProperty("user.home"), ".osrs-map-editor-hotbar.json");

	// User-built composite tile shapes (drawn in the shape builder), persisted to disk.
	// cells[x][y] = -1 (empty) or (path*4 + rotation) — one real tile shape per cell.
	static class CustomShape { String name; int[][] cells; }
	private static final File SHAPES_FILE =
		new File(System.getProperty("user.home"), ".osrs-map-editor-shapes.json");
	private final java.util.List<CustomShape> customShapes = new java.util.ArrayList<>();
	private JPanel customShapesRow;            // rebuilt when a custom shape is added/removed
	private javax.swing.JLabel stampPreview;   // shows the currently-armed composite shape (rotates with ↻)

	// Customisable left sidebar: the 8 tools are fixed; the free slots below can hold
	// user-pinned toggles chosen in File > Settings. Persisted to disk.
	private static final class PinItem
	{
		final String name;
		final java.util.function.BooleanSupplier get; // non-null => a toggle
		final java.util.function.Consumer<Boolean> set;
		final Runnable action;                          // non-null => a one-shot button
		PinItem(String name, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set)
		{
			this.name = name; this.get = get; this.set = set; this.action = null;
		}
		PinItem(String name, Runnable action)
		{
			this.name = name; this.action = action; this.get = null; this.set = null;
		}
		boolean isToggle() { return get != null; }
	}
	private final java.util.List<PinItem> pinnable = new java.util.ArrayList<>();
	private final java.util.List<String> pinnedNames = new java.util.ArrayList<>();
	private JPanel pinnedArea;
	private static final File SIDEBAR_FILE =
		new File(System.getProperty("user.home"), ".osrs-map-editor-sidebar.json");

	private final HotSlot[] hotSlots = new HotSlot[HOT_KEYS.length];
	private final javax.swing.JComponent[] hotComps = new javax.swing.JComponent[HOT_KEYS.length];
	private final java.awt.image.BufferedImage[] hotIcons =
		new java.awt.image.BufferedImage[HOT_KEYS.length]; // cached model thumbnails
	private int activeHotSlot = -1;

	public MapEditorFrame(MapEditorService service, int initialRegion)
	{
		super("OSRS Map Editor");
		this.service = service;
		this.renderer = new MapRenderer(service);
		this.sceneBuilder = new SceneBuilder(service);
		this.sceneBuilder.setLayerOptions(renderOptions);

		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		// Load saved custom shapes before the side panel builds the Shapes tab from them.
		loadCustomShapes();
		loadObjRot();
		buildPinnable();
		loadPinned();
		// Build the side panel first so the ribbon's panel selector can bind to it.
		JComponent sidePanel = buildSidePanel();

		setJMenuBar(buildMenuBar());
		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.add(buildRibbonBar());
		add(north, BorderLayout.NORTH);
		toolColumn = buildToolColumn();
		add(toolColumn, BorderLayout.WEST);
		canvasScroll = new JScrollPane(canvas2D);
		// Minimum sizes stop the split divider from squeezing either pane to nothing
		// (which looked like the 2D view "disappearing" into full 3D).
		canvasScroll.setMinimumSize(new Dimension(140, 140));
		canvas3D.setMinimumSize(new Dimension(140, 140));
		splitPane = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
		splitPane.setResizeWeight(0.5);
		splitPane.setContinuousLayout(true);
		// The divider can only be positioned once the pane has a real size —
		// setting it too early collapses one side (looked like "full 3D").
		splitPane.addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override public void componentResized(java.awt.event.ComponentEvent e)
			{
				if (needDividerReset && splitPane.getWidth() > 0 && splitPane.getHeight() > 0)
				{
					needDividerReset = false;
					splitPane.setDividerLocation(0.5);
				}
			}
		});
		JPanel east = new JPanel(new BorderLayout());
		east.add(buildMinimap(), BorderLayout.NORTH);
		east.add(sidePanel, BorderLayout.CENTER);
		eastPanel = east;
		add(east, BorderLayout.EAST);
		// Map + hotbar live together in the CENTER so the east panel spans the full
		// height (no dead gap in the bottom-right corner). The hotbar then naturally
		// stops where the right-hand panel begins.
		centerWrap = new JPanel(new BorderLayout());
		hotbar = buildHotbar();
		centerWrap.add(hotbar, BorderLayout.SOUTH);
		add(centerWrap, BorderLayout.CENTER);
		// Thin status bar at the very bottom: hint text on the left, plane controls on the right.
		JPanel statusBar = new JPanel(new BorderLayout());
		statusBar.add(status, BorderLayout.CENTER);
		statusBar.add(buildPlaneControls(), BorderLayout.EAST);
		add(statusBar, BorderLayout.SOUTH);
		loadHotbar();
		installHotkeys();
		updateCenter();

		setSize(1400, 900);
		setLocationRelativeTo(null);

		moveTimer = new javax.swing.Timer(30, e -> tickMovement());
		animTimer = new javax.swing.Timer(33, e -> tickAnimation()); // ~30fps for smooth animation
		pulseTimer = new javax.swing.Timer(350, e -> { pulseOn = !pulseOn; canvas2D.repaint(); });

		// Undo / redo shortcuts
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("control Z"), "undo");
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("control Y"), "redo");
		getRootPane().getActionMap().put("undo", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e) { undo(); }
		});

		// Copy / paste / delete of the object selection.
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("control C"), "copySel");
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("control V"), "pasteSel");
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("DELETE"), "delSel");
		getRootPane().getActionMap().put("copySel", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e) { if (!hotkeysBlocked()) { copySelection(); } }
		});
		getRootPane().getActionMap().put("pasteSel", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e) { if (!hotkeysBlocked()) { pasteSelection(); } }
		});
		getRootPane().getActionMap().put("delSel", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				if (hotkeysBlocked()) { return; }
				if (!selectedLocs.isEmpty()) { deleteSelection(); }
				else if (selectedNpc != null) { deleteSelectedNpc(); }
				else if (selectedLoc != null) { deleteLocation(selectedLoc); }
			}
		});
		getRootPane().getActionMap().put("redo", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e) { redo(); }
		});

		// Keep the 3D renderer sized to the 3D canvas whenever it changes size.
		canvas3D.addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override public void componentResized(java.awt.event.ComponentEvent e)
			{
				if (show3D())
				{
					fit3DToViewport();
					render3DFull();
				}
			}
		});
		// Auto-fit the 2D map to fill its viewport (unless the user manually zoomed).
		canvasScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override public void componentResized(java.awt.event.ComponentEvent e)
			{
				if (show2D())
				{
					if (manual2DZoom) { update2DCanvasSize(); } else { fit2D(); }
				}
			}
		});

		loadInitialRegion(initialRegion);
	}

	// ---- UI construction ----------------------------------------------

	private javax.swing.JMenuBar buildMenuBar()
	{
		javax.swing.JMenuBar mb = new javax.swing.JMenuBar();

		javax.swing.JMenu file = new javax.swing.JMenu("File");
		file.add(menuItem("Open Cache…", e -> openCache()));
		file.add(menuItem("Save", "control S", e -> save()));
		file.add(menuItem("Reload region", e -> reload()));
		file.addSeparator();
		file.add(menuItem("Save to server (TOML + repack)", "control shift S", e -> saveToServer()));
		file.add(menuItem("Export region to TOML…", e -> exportRegionToml()));
		file.add(menuItem("Dump region m/l files…", e -> dumpRegionFiles()));
		file.add(menuItem("Open region m/l files…", e -> openRegionFiles()));
		file.add(menuItem("Open RSPSi .pack…", e -> openPackFile()));
		file.addSeparator();
		file.add(menuItem("Settings (sidebar buttons)…", e -> showSettingsDialog()));
		file.addSeparator();
		file.add(menuItem("Exit", e -> dispose()));
		mb.add(file);

		javax.swing.JMenu edit = new javax.swing.JMenu("Edit");
		edit.add(menuItem("Undo", "control Z", e -> undo()));
		edit.add(menuItem("Redo", "control Y", e -> redo()));
		edit.addSeparator();
		edit.add(menuItem("Flatten current plane…", e -> flattenPlane(false)));
		edit.add(menuItem("Flatten selected area…", e -> flattenPlane(true)));
		mb.add(edit);

		javax.swing.JMenu view = new javax.swing.JMenu("View");
		// Hide only the minimap component (BorderLayout NORTH); the side-panel tabs (CENTER) then
		// grow to fill the freed space. (Use "Side panels" to hide the whole right column.)
		view.add(viewToggle("Minimap", true, v -> { minimap.setVisible(v); relayout(); }));
		view.add(viewToggle("Element hotbar", true, v -> { hotbar.setVisible(v); relayout(); }));
		view.add(viewToggle("Tool column", true, v -> { toolColumn.setVisible(v); relayout(); }));
		view.add(viewToggle("Side panels", true, v -> { eastPanel.setVisible(v); relayout(); }));
		view.addSeparator();
		view.add(viewToggle("Grid", true, v -> { renderOptions.showGrid = v; rerender(); }));
		view.add(viewToggle("All object markers", true, v -> { renderOptions.showObjects = v; sceneDirty = true; rerender(); }));
		view.add(viewToggle("Height tint (2D)", false, v -> { renderOptions.showHeightTint = v; rerender(); }));
		// Map-icon overlays now live on the Terrain ribbon ("Map icons" group).
		view.add(viewToggle("All planes (merge levels)", false, v ->
		{
			viewAllPlanes = v;
			renderOptions.allPlanes = v;
			sceneDirty = true;
			rerender();
		}));
		view.add(viewToggle("Show floor below (ghost)", true, v ->
		{
			renderOptions.ghostLowerPlanes = v;
			rerender();
		}));
		view.add(viewToggle("Spawn names", true, v -> { showSpawnNames = v; canvas2D.repaint(); }));
		mb.add(view);

		javax.swing.JMenu win = new javax.swing.JMenu("Window");
		win.add(menuItem("Go To (world map)…", e -> goToDialog()));
		win.add(menuItem("Add Region…", e -> addRegionDialog()));
		win.add(new javax.swing.JSeparator());
		win.add(menuItem("Pop out 2D View", e -> togglePopout2D()));
		win.add(menuItem("Pop out 3D View", e -> togglePopout3D()));
		mb.add(win);

		javax.swing.JMenu help = new javax.swing.JMenu("Help");
		help.add(menuItem("Keyboard Shortcuts & Features…", "F1", e -> showHelpDialog()));
		mb.add(help);

		return mb;
	}

	/** A scrollable reference of every tool, tab, view toggle and hotkey (Help menu / F1). */
	private void showHelpDialog()
	{
		String css = "<style>body{font-family:sans-serif;font-size:12px;margin:8px;}"
			+ "h2{color:#4FA8FF;margin:12px 0 4px;font-size:14px;border-bottom:1px solid #555;}"
			+ "h3{color:#FFCC33;margin:10px 0 2px;font-size:12px;}"
			+ "b.k{color:#5FE85F;font-family:monospace;}"
			+ "table{border-collapse:collapse;} td{padding:1px 10px 1px 0;vertical-align:top;}</style>";
		StringBuilder h = new StringBuilder("<html>").append(css).append("<body>");
		h.append("<h2>OSRS Map Editor — Shortcuts &amp; Features</h2>");

		h.append("<h3>Global keys (any focus)</h3><table>");
		h.append(krow("Ctrl+Z / Ctrl+Y", "Undo / Redo"));
		h.append(krow("Ctrl+S", "Save the region to the server cache"));
		h.append(krow("Ctrl+C / Ctrl+V", "Copy / paste selected object(s)"));
		h.append(krow("Delete", "Delete the selected object(s) / tile selection"));
		h.append(krow("R or X", "Rotate 90° — selected object/NPC, else the armed placement"));
		h.append(krow("1-5 Q W E R T A S D F G Z X C V B", "Element hotbar slots (right-click a slot to assign the current tool)"));
		h.append(krow("F1", "Open this help"));
		h.append("</table>");

		h.append("<h3>3D view (camera focused)</h3><table>");
		h.append(krow("W A S D", "Move camera (fly) horizontally"));
		h.append(krow("Q / E", "Move camera up / down"));
		h.append(krow("N", "Snap camera to face north (align with 2D)"));
		h.append(krow("Middle-drag", "Rotate (orbit) the camera"));
		h.append(krow("Mouse wheel", "Zoom (0.15x – 30x)"));
		h.append(krow("Left-click", "Select an object, or the ground tile"));
		h.append(krow("Alt + left-drag", "Move the selected object across tiles"));
		h.append(krow("Right-click", "Object menu: rotate / move / delete / copy / paste"));
		h.append(krow("Alt+click (terrain tool)", "Eyedropper — pick the underlay/overlay under the cursor"));
		h.append("</table>");

		h.append("<h3>2D map</h3><table>");
		h.append(krow("Left-click", "Use the current tool (select / paint / place)"));
		h.append(krow("Left-drag on object", "Move it; on empty ground = marquee select"));
		h.append(krow("Middle-drag", "Pan the map"));
		h.append(krow("Mouse wheel", "Zoom (anchored on cursor)"));
		h.append(krow("Right-click", "Context menu (tile / object / paste)"));
		h.append(krow("Shift (painting height)", "Affect the lower plane"));
		h.append(krow("Ctrl (painting height)", "Set absolute height"));
		h.append(krow("Alt+click (terrain tool)", "Eyedropper pick"));
		h.append("</table>");

		h.append("<h3>World map (Go To)</h3><table>");
		h.append(krow("Left-click", "Open that region (or offer to create it)"));
		h.append(krow("Middle-drag", "Pan"));
		h.append(krow("Mouse wheel", "Zoom, anchored on cursor"));
		h.append("</table>");

		h.append("<h2>Tools (left rail)</h2><table>");
		h.append(krow("Select", "Pick / move objects, NPCs and tiles"));
		h.append(krow("Underlay / Overlay", "Paint the picked ground colour / overlay shape"));
		h.append(krow("Height", "Raise/lower terrain; set a fixed height"));
		h.append(krow("Flags", "Paint collision/block flags"));
		h.append(krow("Place", "Place the armed object (id / type / rotation)"));
		h.append(krow("NPC", "Place an NPC spawn"));
		h.append(krow("Delete", "Remove objects/NPCs by clicking"));
		h.append("</table>");

		h.append("<h2>Side tabs</h2><table>");
		h.append(krow("Underlay / Overlay", "Colour palettes; \"Pick from map\" eyedropper. Texture overlays are marked with a <b>T</b> badge in the Overlay tab"));
		h.append(krow("Shapes", "Single-tile overlay shapes + your custom composite shapes"));
		h.append(krow("Objects", "Search objects (type <b>null</b> for unnamed); click to arm for placing"));
		h.append(krow("Kit", "Only multi-model objects (walls/roofs); Model 1/2/3… buttons"));
		h.append(krow("NPCs / Models", "Browse and place NPCs, raw models"));
		h.append("</table>");

		h.append("<h2>View toggles (View ribbon)</h2><table>");
		h.append(krow("Grid / Objects / Flags / Textures", "Show/hide those layers"));
		h.append(krow("Height tint / Tile heights", "Relief colour / per-tile height numbers (3D)"));
		h.append(krow("Buildable", "Red = non-flat tiles a building would tilt/float on"));
		h.append(krow("Wall conflicts", "Red = 2+ walls stacked on one tile (one vanishes in-game)"));
		h.append(krow("Neighbours", "Dimmed 5-tile strip of all 8 surrounding regions (3D) to line up edges"));
		h.append(krow("All planes / Ghost below", "Merge planes / show the floor below dimmed"));
		h.append("</table>");

		h.append("<h2>Object placement (walls &amp; kits)</h2>");
		h.append("Select an object → it arms automatically. Multi-model objects show <b>Model 1/2/3</b> buttons"
			+ " (walls: straight/corner/L-corner/square/diagonal). Rotate with the <b>Rot</b> button or <b class='k'>R</b>/<b class='k'>X</b>."
			+ "<br>On the 2D map walls are colour-coded: <font color='#FFD83B'>straight = yellow</font>,"
			+ " <font color='#4FA8FF'>corner = blue triangle</font>, <font color='#5FE85F'>diagonal = green</font>, square = white.");

		h.append("</body></html>");

		javax.swing.JEditorPane pane = new javax.swing.JEditorPane("text/html", h.toString());
		pane.setEditable(false);
		pane.setCaretPosition(0);
		javax.swing.JScrollPane sp = new javax.swing.JScrollPane(pane);
		sp.setPreferredSize(new Dimension(560, 620));
		javax.swing.JDialog d = new javax.swing.JDialog(this, "Keyboard Shortcuts & Features", false);
		d.add(sp);
		d.pack();
		d.setLocationRelativeTo(this);
		d.setVisible(true);
	}

	private static String krow(String key, String desc)
	{
		return "<tr><td><b class='k'>" + key + "</b></td><td>" + desc + "</td></tr>";
	}

	private javax.swing.JMenuItem menuItem(String text, java.awt.event.ActionListener a)
	{
		return menuItem(text, null, a);
	}

	private javax.swing.JMenuItem menuItem(String text, String accel, java.awt.event.ActionListener a)
	{
		javax.swing.JMenuItem mi = new javax.swing.JMenuItem(text);
		if (accel != null)
		{
			mi.setAccelerator(javax.swing.KeyStroke.getKeyStroke(accel));
		}
		mi.addActionListener(a);
		return mi;
	}

	private javax.swing.JCheckBoxMenuItem viewToggle(String text, boolean sel, java.util.function.Consumer<Boolean> onChange)
	{
		javax.swing.JCheckBoxMenuItem mi = new javax.swing.JCheckBoxMenuItem(text, sel);
		mi.addActionListener(e -> onChange.accept(mi.isSelected()));
		return mi;
	}

	private void relayout()
	{
		getContentPane().revalidate();
		getContentPane().repaint();
	}

	/**
	 * The ribbon: a quick-access strip (Save/Open/Reload) plus focused Office-style tabs
	 * — Tools / Terrain / Objects / View / Settings — each swapping the command strip below.
	 */
	private JComponent buildRibbonBar()
	{
		JPanel root = new JPanel(new java.awt.BorderLayout());
		root.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2A2E33)));

		java.awt.CardLayout cards = new java.awt.CardLayout();
		JPanel cardPanel = new JPanel(cards);
		cardPanel.add(buildToolsCard(), "Tools");
		cardPanel.add(buildTerrainCard(), "Terrain");
		cardPanel.add(buildObjectsCard(), "Objects");
		cardPanel.add(buildViewCard(), "View");
		cardPanel.add(buildSettingsCard(), "Settings");

		// Tab row: quick-access buttons + the contextual tab toggles.
		JPanel tabRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 3));
		JButton save = barButton("Save", "💾", "Save changes to the cache (Ctrl+S)", e -> save());
		save.putClientProperty("JButton.buttonType", "default");
		tabRow.add(save);
		tabRow.add(barButton("Open", "📁", "Open a different cache folder", e -> openCache()));
		tabRow.add(barButton("Reload", "🔄", "Reload region from cache (discards edits)", e -> reload()));
		tabRow.add(vsep());
		ButtonGroup tabs = new ButtonGroup();
		for (String n : new String[]{"Tools", "Terrain", "Objects", "View", "Settings"})
		{
			JToggleButton tb = new JToggleButton(n, n.equals("Tools"));
			tb.setFocusable(false);
			tb.setMargin(new java.awt.Insets(5, 16, 5, 16));
			tb.setFont(tb.getFont().deriveFont(java.awt.Font.BOLD));
			tb.addActionListener(e -> cards.show(cardPanel, n));
			tabs.add(tb);
			tabRow.add(tb);
		}
		cards.show(cardPanel, "Tools");

		root.add(tabRow, java.awt.BorderLayout.NORTH);
		root.add(cardPanel, java.awt.BorderLayout.CENTER);

		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(javax.swing.KeyStroke.getKeyStroke("control S"), "save");
		root.getActionMap().put("save", new javax.swing.AbstractAction()
		{
			public void actionPerformed(java.awt.event.ActionEvent e) { save(); }
		});
		return root;
	}

	/** The View tab: the Show / Camera groups (split back out of Terrain to keep both tabs calm). */
	private JComponent buildViewCard()
	{
		JPanel c = ribbonRow();
		addViewGroups(c);
		return c;
	}

	// ---- ribbon cards: Office-style groups (boxed sections with captions) --------

	private JPanel ribbonRow()
	{
		JPanel p = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, 0, 0));
		p.setBorder(BorderFactory.createEmptyBorder(2, 4, 1, 4));
		return p;
	}

	/**
	 * A {@link java.awt.FlowLayout} that wraps to more rows when the content is wider than the
	 * container AND reports the true multi-row height (plain FlowLayout always reports a single
	 * row, so wrapped rows get clipped — that was cutting off the Overlay-shape picker).
	 */
	private static class WrapLayout extends java.awt.FlowLayout
	{
		WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

		@Override public Dimension preferredLayoutSize(java.awt.Container target) { return layoutSize(target, true); }

		@Override public Dimension minimumLayoutSize(java.awt.Container target)
		{
			Dimension d = layoutSize(target, false);
			d.width -= (getHgap() + 1);
			return d;
		}

		private Dimension layoutSize(java.awt.Container target, boolean preferred)
		{
			synchronized (target.getTreeLock())
			{
				java.awt.Container c = target;
				while (c.getSize().width == 0 && c.getParent() != null) { c = c.getParent(); }
				int targetWidth = c.getSize().width;
				if (targetWidth == 0) { targetWidth = Integer.MAX_VALUE; }
				int hgap = getHgap(), vgap = getVgap();
				java.awt.Insets insets = target.getInsets();
				int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
				Dimension dim = new Dimension(0, 0);
				int rowWidth = 0, rowHeight = 0;
				for (int i = 0; i < target.getComponentCount(); i++)
				{
					java.awt.Component m = target.getComponent(i);
					if (!m.isVisible()) { continue; }
					Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
					if (rowWidth + d.width > maxWidth && rowWidth > 0)
					{
						dim.width = Math.max(dim.width, rowWidth);
						dim.height += (dim.height > 0 ? vgap : 0) + rowHeight;
						rowWidth = 0; rowHeight = 0;
					}
					rowWidth += (rowWidth > 0 ? hgap : 0) + d.width;
					rowHeight = Math.max(rowHeight, d.height);
				}
				dim.width = Math.max(dim.width, rowWidth);
				dim.height += (dim.height > 0 ? vgap : 0) + rowHeight;
				dim.width += insets.left + insets.right + hgap * 2;
				dim.height += insets.top + insets.bottom + vgap * 2;
				return dim;
			}
		}
	}

	/** A ribbon group: controls in a row, a caption below, a thin divider on the right. */
	private JComponent ribbonGroup(String title, JComponent... items)
	{
		JPanel content = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		content.setOpaque(false);
		for (JComponent it : items)
		{
			content.add(it);
		}
		JLabel cap = new JLabel(title, javax.swing.SwingConstants.CENTER);
		cap.setForeground(new Color(0x808893));
		cap.setFont(cap.getFont().deriveFont(java.awt.Font.PLAIN, 10.5f));
		cap.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		JPanel g = new JPanel(new BorderLayout(0, 0));
		g.setOpaque(false);
		g.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x30353C)),
			BorderFactory.createEmptyBorder(4, 9, 3, 9)));
		g.add(content, BorderLayout.CENTER);
		g.add(cap, BorderLayout.SOUTH);
		return g;
	}

	private JButton miniBtn(String text, Runnable onClick)
	{
		JButton b = new FlatButton(text);
		b.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		b.addActionListener(e -> onClick.run());
		return b;
	}

	/** [−] [slider] [+] value — a slider flanked by stepper buttons and a live value. */
	private JComponent steppedSlider(javax.swing.JSlider s, JLabel valueLabel)
	{
		s.setPreferredSize(new Dimension(92, 22));
		s.setOpaque(false);
		valueLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
		JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
		p.setOpaque(false);
		p.add(miniBtn("−", () -> s.setValue(s.getValue() - 1)));
		p.add(s);
		p.add(miniBtn("+", () -> s.setValue(s.getValue() + 1)));
		p.add(valueLabel);
		return p;
	}

	/** A ribbon tool toggle, kept in sync with the left tool column via {@link #setTool}. */
	private javax.swing.AbstractButton ribbonTool(String text, Tool t)
	{
		JToggleButton b = new FlatToggle(text, makeToolIcon(t), tool == t);
		makeBig(b);
		b.setToolTipText(text);
		b.addActionListener(e -> setTool(t));
		ribbonToolGroup.add(b);
		ribbonTools.put(t, b);
		return b;
	}

	/** Compact "label [field]" for the ribbon. */
	private JComponent rField(String label, JTextField f, int cols)
	{
		f.setColumns(cols);
		f.setPreferredSize(new Dimension(cols * 9 + 12, 24));
		JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		p.setOpaque(false);
		JLabel l = new JLabel(label);
		l.setForeground(new Color(0xB4B8BE));
		p.add(l);
		p.add(f);
		return p;
	}

	/** The single-tile shape grid (full tile + 11 shapes) + a rotate button. */
	private JComponent buildShapePicker()
	{
		JButton[] btns = new JButton[12];
		JPanel grid = new JPanel(new GridLayout(2, 6, 2, 2));
		grid.setOpaque(false);
		for (int p = 0; p <= 11; p++)
		{
			final int path = p;
			JButton b = new JButton(shapeIcon(p, parse(overlayRotField, 0)));
			b.setMargin(new java.awt.Insets(1, 1, 1, 1));
			b.setFocusable(false);
			b.setToolTipText(p == 0 ? "Full tile" : "Shape " + p + " — use Rot for the other sides");
			// Single-tile shape: paint one tile; leaving any composite-stamp mode.
			b.addActionListener(e -> { pasteTilesMode = false; overlayPathField.setText(String.valueOf(path)); setTool(Tool.OVERLAY); });
			btns[p] = b;
			grid.add(b);
		}
		JButton rot = new JButton("Rot");
		rot.setFocusable(false);
		rot.setMargin(new java.awt.Insets(1, 6, 1, 6));
		rot.setToolTipText("Rotate the single-tile shape 90°");
		rot.addActionListener(e ->
		{
			int r = (parse(overlayRotField, 0) + 1) & 3;
			overlayRotField.setText(String.valueOf(r));
			for (int i = 0; i <= 11; i++) { btns[i].setIcon(shapeIcon(i, r)); }
		});
		JPanel topRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
		topRow.setOpaque(false);
		topRow.add(grid);
		topRow.add(rot);
		return topRow;
	}

	private JLabel shapeSectionLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(new Color(0x9AA0A6));
		l.setFont(l.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 0));
		return l;
	}

	/** The whole "Shapes" side tab: single-tile shapes, preset composites, and custom composites. */
	private JComponent buildShapesTab()
	{
		JPanel root = new JPanel();
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
		root.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		root.add(shapeSectionLabel("Tile shapes  (paint one tile)"));
		JComponent single = buildShapePicker();
		single.setAlignmentX(LEFT_ALIGNMENT);
		root.add(single);

		JCheckBox underToo = new JCheckBox("+underlay");
		underToo.setFocusable(false);
		underToo.setAlignmentX(LEFT_ALIGNMENT);
		underToo.setToolTipText("Also set the underlay id on the uncovered part of a shaped overlay tile");
		underToo.addActionListener(e -> paintUnderlayToo = underToo.isSelected());
		root.add(underToo);

		root.add(javax.swing.Box.createVerticalStrut(10));
		root.add(shapeSectionLabel("My custom shapes  (click one, then click the map)"));
		// Grid so shapes wrap 5-per-row instead of running off in one line.
		customShapesRow = new JPanel(new GridLayout(0, 5, 3, 3));
		customShapesRow.setOpaque(false);
		customShapesRow.setAlignmentX(LEFT_ALIGNMENT);
		rebuildCustomShapesRow();
		root.add(customShapesRow);

		// A bordered preview box (like the model viewer) showing the shape you're about to place,
		// with a Rotate button beside it. Updates as you rotate.
		root.add(javax.swing.Box.createVerticalStrut(8));
		JPanel previewRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		previewRow.setOpaque(false);
		previewRow.setAlignmentX(LEFT_ALIGNMENT);
		stampPreview = new javax.swing.JLabel("", javax.swing.SwingConstants.CENTER);
		stampPreview.setPreferredSize(new Dimension(96, 96));
		stampPreview.setBorder(BorderFactory.createLineBorder(new Color(0x40, 0x44, 0x4a)));
		stampPreview.setToolTipText("The shape you're about to place (updates when you rotate)");
		previewRow.add(stampPreview);
		JButton rotC = new JButton("Rotate");
		rotC.setFocusable(false);
		rotC.setToolTipText("Rotate the selected custom shape 90° before placing");
		rotC.addActionListener(e -> rotateTileStamp());
		previewRow.add(rotC);
		root.add(previewRow);

		JScrollPane sp = new JScrollPane(root);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.setBorder(null);
		return sp;
	}

	/** Rebuild the custom-shape buttons row (after add/delete) + the trailing "＋ Add" button. */
	private void rebuildCustomShapesRow()
	{
		if (customShapesRow == null)
		{
			return;
		}
		customShapesRow.removeAll();
		for (CustomShape cs : customShapes)
		{
			final CustomShape shape = cs;
			JButton b = new JButton(cellsIcon(cs.cells, 26));
			b.setMargin(new java.awt.Insets(1, 1, 1, 1));
			b.setFocusable(false);
			b.setToolTipText("<html><b>" + cs.name + "</b> — click, then click the map to place it. Right-click to edit/delete.</html>");
			b.addActionListener(e -> loadCustomStamp(shape.cells, shape.name));
			b.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override public void mousePressed(java.awt.event.MouseEvent e)
				{
					if (!javax.swing.SwingUtilities.isRightMouseButton(e))
					{
						return;
					}
					javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
					javax.swing.JMenuItem edit = new javax.swing.JMenuItem("Edit");
					edit.addActionListener(a -> openShapeBuilder(shape));
					m.add(edit);
					javax.swing.JMenuItem del = new javax.swing.JMenuItem("Delete");
					del.addActionListener(a ->
					{
						customShapes.remove(shape);
						saveCustomShapes();
						rebuildCustomShapesRow();
					});
					m.add(del);
					m.show(b, e.getX(), e.getY());
				}
			});
			customShapesRow.add(b);
		}
		JButton add = new JButton("+ Add");
		add.setFocusable(false);
		add.setToolTipText("Draw a new custom tile shape");
		add.addActionListener(e -> openShapeBuilder());
		customShapesRow.add(add);
		// Keep the 5-wide grid from stretching tall in the vertical layout.
		customShapesRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, customShapesRow.getPreferredSize().height));
		customShapesRow.revalidate();
		customShapesRow.repaint();
	}

	private JComponent buildToolsCard()
	{
		JPanel c = ribbonRow();
		c.add(ribbonGroup("Select",
			ribbonTool("Select", Tool.SELECT),
			ribbonTool("Delete", Tool.DELETE_OBJECT)));
		selFilterCombo = new javax.swing.JComboBox<>(new String[]{
			"All", "Objects only", "Ground decor", "Walls"});
		selFilterCombo.setToolTipText("<html>When a tile has stacked pieces, restrict what a 2D click grabs.<br>"
			+ "Tip: click the same tile again to cycle down through everything on it.</html>");
		selFilterCombo.addActionListener(e ->
		{
			selFilter = selFilterCombo.getSelectedIndex();
			cycleTileX = cycleTileY = -1; // reset cycle on filter change
		});
		c.add(ribbonGroup("Click selects", selFilterCombo));
		c.add(ribbonGroup("Edit",
			barButton("Undo", "↶", "Undo (Ctrl+Z)", e -> undo()),
			barButton("Redo", "↷", "Redo (Ctrl+Y)", e -> redo())));
		c.add(ribbonGroup("Navigate",
			barButton("Go To", "🗺", "Open the world map to jump between regions", e -> goToDialog()),
			barButton("Add Region", "➕", "Create a new map square (next free id)", e -> addRegionDialog())));
		JCheckBox compassCb = barCheck("Compass", true, s ->
		{
			showCompass = s;
			canvas3D.repaint();
			if (splitMode) { canvas2D.repaint(); }
		});
		compassCb.setToolTipText("Show the rotating N/S/E/W compass in the 3D view corner");
		c.add(ribbonGroup("View", compassCb));
		return c;
	}

	private JComponent buildTerrainCard()
	{
		JPanel c = ribbonRow();
		// (Paint tools Underlay/Overlay/Height/Flags live on the left tool column — no ribbon dup.)

		JLabel brushVal = new JLabel(brushLabel(brushSize));
		brushSlider.setToolTipText("Brush radius: 0 = 1 tile, 1 = 3×3, … up to 20 (41×41)");
		brushSlider.addChangeListener(e ->
		{
			brushSize = brushSlider.getValue();
			brushVal.setText(brushLabel(brushSize));
			if (show2D()) { canvas2D.repaint(); }
		});
		c.add(ribbonGroup("Brush size", steppedSlider(brushSlider, brushVal)));

		javax.swing.JSpinner hVal = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(2, 0, 255, 1));
		hVal.setToolTipText("<html>Fixed target height — clicking sets tiles to exactly this <b>absolute</b>"
			+ " height (the same number the Tile-heights overlay shows), on any plane. On plane 1+ this is"
			+ " computed relative to the plane below, so e.g. setting a bridge deck to its bank height merges them.</html>");
		JCheckBox hFix = barCheck("fixed", false, null);
		hFix.setToolTipText("ON: click sets tiles to the fixed height (won't exceed it). OFF: sculpt by step.");
		hFix.addActionListener(e ->
		{
			heightField.setText(hFix.isSelected() ? String.valueOf(hVal.getValue()) : "");
			setTool(Tool.HEIGHT);
			status.setText(hFix.isSelected()
				? " Fixed height " + hVal.getValue() + " — every click sets tiles to exactly this"
				: " Sculpt mode — click raises / right-click lowers by the step");
		});
		hVal.addChangeListener(e ->
		{
			if (hFix.isSelected()) { heightField.setText(String.valueOf(hVal.getValue())); }
		});
		c.add(ribbonGroup("Fixed height", hFix, hVal));

		JLabel stepVal = new JLabel(String.valueOf(heightStep));
		heightSlider.setToolTipText("Sculpt step (used when 'fixed' is OFF): each click raises / lowers by this");
		heightSlider.addChangeListener(e ->
		{
			heightStep = heightSlider.getValue();
			stepVal.setText(String.valueOf(heightStep));
		});
		c.add(ribbonGroup("Sculpt step", steppedSlider(heightSlider, stepVal)));

		JCheckBox area = barCheck("Area fill", false, s ->
		{
			areaMode = s; if (!s) { areaX0 = -1; canvas2D.repaint(); }
		});
		area.setToolTipText("Drag a rectangle in the 2D view to fill it with the active tool");
		c.add(ribbonGroup("Fill", area));

		// Level / Smooth / Bridge operations collapsed into one compact dropdown + Apply to save
		// ribbon width. Pick an operation and click Apply (or press Enter in the combo).
		JComboBox<String> opCombo = new JComboBox<>(new String[]{
			"Flatten plane", "Flatten area",
			"Smooth area", "Smooth plane",
			"Make bridge", "Clear bridge"
		});
		opCombo.setPreferredSize(new Dimension(140, 26));
		opCombo.setMaximumSize(new Dimension(140, 26));
		opCombo.setToolTipText("<html><b>Flatten plane/area</b>: level to one height."
			+ "<br><b>Smooth area/plane</b>: round off blocky overlay edges."
			+ "<br><b>Make/Clear bridge</b>: plane-1 bridge deck over an Area-fill selection.</html>");
		JButton opApply = new FlatButton("Apply");
		opApply.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
		opApply.setToolTipText("Run the selected terrain operation");
		opApply.addActionListener(e -> runTerrainOp((String) opCombo.getSelectedItem()));
		c.add(ribbonGroup("Terrain ops", opCombo, opApply));

		// (Overlay-id fields removed — the Overlay palette has its own right-side tab now.)

		// Flag value as a NAMED dropdown (editable, so custom numbers still work). The chosen value
		// is what the Flags tool paints; it's colour-coded on the 2D map (blocked=red, bridge=blue …).
		String[] flagItems = {
			"1 — Blocked (collision)",
			"2 — Bridge (link below)",
			"3 — Blocked + Bridge",
			"4 — Flag 4",
			"8 — Flag 8",
			"0 — None (clear)"
		};
		JComboBox<String> flagCombo = new JComboBox<>(flagItems);
		flagCombo.setEditable(true);
		flagCombo.setSelectedItem("1 — Blocked (collision)");
		flagCombo.setPreferredSize(new Dimension(150, 26));
		flagCombo.setMaximumSize(new Dimension(150, 26));
		flagCombo.setToolTipText("<html>Value the Flags tool paints. Blocked=1 (red), Bridge=2 (blue)."
			+ " Pick a named value or type a custom number.</html>");
		flagCombo.addActionListener(e ->
		{
			Object sel = flagCombo.getSelectedItem();
			int v = parseLeadingInt(sel == null ? "" : sel.toString(), 1);
			settingsField.setText(String.valueOf(v));
			setTool(Tool.SETTINGS);
			status.setText(" Flag value " + v + " — paint tiles with the Flags tool (colour-coded on the 2D map)");
		});
		c.add(ribbonGroup("Flag value", flagCombo));

		// Map-icon overlays — moved here from the View menu so they sit with the other 2D toggles.
		JPanel icons = new JPanel(new java.awt.GridLayout(0, 1, 0, 1));
		icons.setOpaque(false);
		JCheckBox objIconsCb = barCheck("Object icons", false, s -> { renderOptions.showObjectMapIcons = s; rerender(); });
		objIconsCb.setToolTipText("Icons from objects' map_icon that are baked into the cache");
		icons.add(objIconsCb);
		JCheckBox srvIconsCb = barCheck("Server objects", false, s -> { renderOptions.showServerSpawns = s; sceneDirty = true; rerender(); });
		srvIconsCb.setToolTipText("<html>Objects the server spawns at runtime from <b>ClientObj.java</b> (banks, altars,"
			+ " benches, icon markers) — drawn as object markers with a purple corner tag + their map_icon.</html>");
		icons.add(srvIconsCb);
		JCheckBox worldIconsCb = barCheck("World icons", false, s -> { renderOptions.showWorldMapIcons = s; rerender(); });
		worldIconsCb.setToolTipText("Fullscreen world-map compositemap markers (dungeon/transport)");
		icons.add(worldIconsCb);
		c.add(ribbonGroup("Map icons", icons));

		// (Plane / All planes / Ghost below moved to the bottom status bar — see buildPlaneControls.)
		// (Show / Camera groups live in their own View tab now — see buildViewCard.)

		return c;
	}

	/** Plane selector + All-planes / Ghost-below toggles, for the bottom status bar (right side). */
	private JComponent buildPlaneControls()
	{
		planeCombo.setPreferredSize(new Dimension(52, 24));
		planeCombo.addActionListener(e ->
		{
			plane = (Integer) planeCombo.getSelectedItem();
			selectedLoc = null; movingLoc = null; sceneBuilder.setHighlight(null);
			sceneDirty = true; updateSelCorners(); rerender();
		});
		JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 1));
		p.setOpaque(false);
		JLabel pl = new JLabel("Plane");
		pl.setForeground(new Color(0xB4B8BE));
		p.add(pl);
		p.add(planeCombo);
		p.add(barCheck("All planes", false, s -> { viewAllPlanes = s; renderOptions.allPlanes = s; sceneDirty = true; rerender(); }));
		p.add(barCheck("Ghost below", true, s -> { renderOptions.ghostLowerPlanes = s; rerender(); }));
		return p;
	}

	/** Runs one of the collapsed Level / Smooth / Bridge terrain operations by name. */
	private void runTerrainOp(String op)
	{
		if (op == null) { return; }
		switch (op)
		{
			case "Flatten plane": flattenPlane(false); break;
			case "Flatten area":  flattenPlane(true);  break;
			case "Smooth area":   smoothOverlay(true); break;
			case "Smooth plane":  smoothOverlay(false); break;
			case "Make bridge":   makeBridge();  break;
			case "Clear bridge":  clearBridge(); break;
			default: break;
		}
	}

	/** Parses the first integer in a string (e.g. "2 — Bridge" → 2), or {@code def} if none. */
	private static int parseLeadingInt(String s, int def)
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(s);
		return m.find() ? Integer.parseInt(m.group()) : def;
	}

	private JComponent buildObjectsCard()
	{
		JPanel c = ribbonRow();
		c.add(ribbonGroup("Place",
			ribbonTool("Object", Tool.PLACE_OBJECT),
			ribbonTool("NPC", Tool.PLACE_NPC)));
		spawnsBox = barCheck("Show spawns", false, s -> { showSpawns = s; sceneDirty = true; rerender(); });
		spawnsBox.setToolTipText("Overlay server NPC (purple) and object (cyan) spawns");
		c.add(ribbonGroup("Spawns", spawnsBox,
			barCheck("Names", true, s -> { showSpawnNames = s; canvas2D.repaint(); })));
		c.add(ribbonGroup("Object",
			rField("id", objectIdField, 6),
			rField("type", objectTypeField, 3),
			rField("rot", objectRotField, 2)));
		JLabel hint = new JLabel("Pick from Objects / NPCs / Models →");
		hint.setForeground(new Color(0x9AA0A6));
		c.add(ribbonGroup("Browse", hint));
		return c;
	}

	/** Adds the (former View-ribbon) Show / Camera / Zoom groups to the given ribbon row. */
	private void addViewGroups(JPanel c)
	{
		// --- Object layers: the 7 per-category visibility toggles (3 cols → 3 rows). ---
		JPanel objLayers = new JPanel(new java.awt.GridLayout(0, 3, 6, 2));
		objLayers.setOpaque(false);
		JCheckBox sceneryCb = barCheck("Scenery", true, s -> { renderOptions.showScenery = s; sceneDirty = true; rerender(); });
		sceneryCb.setToolTipText("Scenery objects (types 10–11) — orange markers.");
		objLayers.add(sceneryCb);
		JCheckBox roofsCb = barCheck("Roofs", true, s -> { renderOptions.showRoofs = s; sceneDirty = true; rerender(); });
		roofsCb.setToolTipText("Roof objects (types 12–21) — purple markers.");
		objLayers.add(roofsCb);
		JCheckBox wallDecorCb = barCheck("Wall decor", true, s -> { renderOptions.showWallDecor = s; sceneDirty = true; rerender(); });
		wallDecorCb.setToolTipText("Wall-decoration objects (types 4–8) — cyan ticks.");
		objLayers.add(wallDecorCb);
		JCheckBox wallsCb = barCheck("Walls", true, s -> { renderOptions.showWalls = s; sceneDirty = true; rerender(); });
		wallsCb.setToolTipText("Wall objects (straight/corner/diagonal, types 0–3, 9) that aren't doors.");
		objLayers.add(wallsCb);
		JCheckBox doorsCb = barCheck("Doors", true, s -> { renderOptions.showDoors = s; sceneDirty = true; rerender(); });
		doorsCb.setToolTipText("Wall objects named door/gate.");
		objLayers.add(doorsCb);
		JCheckBox groundCb = barCheck("Ground decor", true, s -> { renderOptions.showGroundDecor = s; sceneDirty = true; rerender(); });
		groundCb.setToolTipText("Ground-decoration objects (type 22) — blue dots.");
		objLayers.add(groundCb);
		JCheckBox iconsCb = barCheck("Icons", true, s -> { renderOptions.showIcons = s; sceneDirty = true; rerender(); });
		iconsCb.setToolTipText("Objects carrying a map_icon (banks, altars, minigames, …).");
		objLayers.add(iconsCb);
		c.add(ribbonGroup("Object layers", objLayers));

		// --- Map: general display toggles (2 cols). ---
		JPanel mapPanel = new JPanel(new java.awt.GridLayout(0, 2, 6, 2));
		mapPanel.setOpaque(false);
		mapPanel.add(barCheck("Grid", true, s -> { renderOptions.showGrid = s; rerender(); }));
		JCheckBox texCb = barCheck("Textures", true, s -> { sceneBuilder.setTextures(s); sceneDirty = true; rerender(); });
		texCb.setToolTipText("<html>3D terrain/model textures. Turn OFF to match a client with ground textures"
			+ " disabled — textured overlays then show their flat colour (e.g. the black checker floor).</html>");
		mapPanel.add(texCb);
		mapPanel.add(barCheck("Flags", false, s -> { renderOptions.showFlags = s; rerender(); }));
		mapPanel.add(barCheck("Height tint", false, s -> { renderOptions.showHeightTint = s; rerender(); }));
		mapPanel.add(barCheck("Elements bar", true, s -> { hotbar.setVisible(s); relayout(); }));
		c.add(ribbonGroup("Map", mapPanel));

		// --- Diagnostics: overlays for spotting problems (2 cols). ---
		JPanel diag = new JPanel(new java.awt.GridLayout(0, 2, 6, 2));
		diag.setOpaque(false);
		JCheckBox heightsCb = barCheck("Tile heights", false, s ->
		{
			showHeights = s;
			canvas3D.repaint();
			if (splitMode) { canvas2D.repaint(); }
		});
		heightsCb.setToolTipText("<html>Overlay the height value of every tile on the 3D view (current plane)."
			+ " Higher number = higher ground. Useful for matching bridge/bank levels between planes.</html>");
		diag.add(heightsCb);
		JCheckBox buildCb = barCheck("Buildable", false, s ->
		{
			showBuildCheck = s;
			canvas3D.repaint();
			if (splitMode) { canvas2D.repaint(); }
		});
		buildCb.setToolTipText("<html>Building-check overlay: paints <b>red</b> on tiles that are NOT flat"
			+ " (sloped or on a cliff edge), where a building would tilt or float. Clear = flat = safe to build."
			+ " Deeper red = steeper.</html>");
		diag.add(buildCb);
		JCheckBox conflictCb = barCheck("Wall conflicts", false, s ->
		{
			showConflicts = s;
			canvas3D.repaint();
			if (splitMode) { canvas2D.repaint(); }
		});
		conflictCb.setToolTipText("<html>Flags <b>red</b> any tile with <b>two or more walls stacked</b> on it."
			+ " OSRS only draws one wall per tile, so the others go missing in-game. Use proper corner"
			+ " walls (type 1/2) instead of stacking two straight walls.</html>");
		diag.add(conflictCb);
		JCheckBox neighborsCb = barCheck("Neighbours", false, s ->
		{
			showNeighbors = s;
			sceneBuilder.invalidateNeighbors();
			sceneDirty = true;
			neighborSig2D = null;
			update2DCanvasSize();
			if (show2D() || splitMode) { render2D(); }
			if (show3D()) { render3DFull(); }
		});
		neighborsCb.setToolTipText("<html>Show a dimmed <b>5-tile</b> strip of the 4 adjacent regions around this one"
			+ " in the <b>2D and 3D views</b>, so you can line up terrain/objects across region edges.</html>");
		diag.add(neighborsCb);
		c.add(ribbonGroup("Diagnostics", diag));

		// (Planes group moved to the Terrain ribbon.)

		JToggleButton view3d = barToggle("3D", "3D view — middle-drag rotate · wheel zoom · WASD move");
		view3d.addActionListener(e -> toggle3D(view3d.isSelected()));
		JToggleButton splitBtn = barToggle("Split", "Show 2D and 3D together (drag the divider)");
		splitBtn.addActionListener(e -> toggleSplit(splitBtn.isSelected()));
		JCheckBox animBox = barCheck("Animate", false, null);
		animBox.setToolTipText("Animate NPCs/objects in 3D (choppy — software renderer)");
		animBox.addActionListener(e ->
		{
			if (animBox.isSelected() && !service.hasAnimatableModels())
			{
				animBox.setSelected(false);
				JOptionPane.showMessageDialog(this,
					"This cache's models load without vertex-group / skeletal skin data,\n"
						+ "so they can't be deformed by animations (nothing to move).",
					"Animation unavailable", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			toggleAnimate(animBox.isSelected());
		});
		c.add(ribbonGroup("Camera", view3d, splitBtn,
			barButton("", "⇄", "Swap split orientation (side-by-side / stacked)", e ->
			{
				splitHorizontal = !splitHorizontal;
				if (splitMode)
				{
					// Swap in place — no tear-down — then re-centre the divider.
					updateCenter();
					resetDivider();
				}
			}),
			animBox));
	}

	/** Settings ribbon: 3D fly speed + render-quality-while-moving toggle. */
	private JComponent buildSettingsCard()
	{
		JPanel c = ribbonRow();

		// WASD/QE fly speed.
		javax.swing.JSlider speed = new javax.swing.JSlider(20, 300, (int) moveStep);
		JLabel speedVal = new JLabel(String.valueOf((int) moveStep));
		speed.setToolTipText("How fast WASD / QE fly the camera through the 3D scene. Drag right for faster.");
		speed.addChangeListener(e ->
		{
			moveStep = speed.getValue();
			speedVal.setText(String.valueOf(speed.getValue()));
		});
		c.add(ribbonGroup("Move speed (WASD)", steppedSlider(speed, speedVal)));

		// Render quality while moving: full-res (crisp, heavier) vs downscaled (smooth, softer).
		JToggleButton sharp = barToggle("Sharp while moving",
			"<html><b>ON</b>: render at full resolution even while moving/rotating — crisp with no softening,"
			+ " but needs a stronger PC to stay smooth.<br><b>OFF</b> (default): render at reduced resolution"
			+ " while moving for high frame-rate, then snap to full crispness the instant you stop.</html>");
		sharp.setSelected(sharpWhileMoving);
		sharp.addActionListener(e ->
		{
			sharpWhileMoving = sharp.isSelected();
			if (show3D()) { render3DFull(); }
		});
		c.add(ribbonGroup("Render quality", sharp));

		return c;
	}

	/** A thin vertical separator sized for the tab row. */
	private JComponent vsep()
	{
		javax.swing.JSeparator s = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
		s.setPreferredSize(new Dimension(1, 26));
		return s;
	}

	// ---- toolbar widget helpers (consistent look) ----------------------

	// ---- Flat "ribbon" button chrome -------------------------------------------------
	// A professional Office-style ribbon: buttons are borderless and only reveal a
	// rounded background on hover / press / selection, with an accent underline when a
	// toggle is active. Every ribbon control routes through here so the look is uniform.
	private static final Color RB_TEXT = new Color(0xD6DAE0);

	// A large ribbon button = icon stacked above a label (Office/Explorer style). Emoji
	// glyphs are enlarged via an HTML span so they read as real ribbon icons.
	private static String bigLabelHtml(String icon, String text)
	{
		return "<html><center><span style='font-size:17pt'>" + icon
			+ "</span><br><span style='font-size:9pt'>" + text + "</span></center></html>";
	}

	private class FlatButton extends JButton
	{
		FlatButton(String t) { super(t); flatInit(this); }
	}

	private class FlatToggle extends JToggleButton
	{
		FlatToggle(String t) { super(t); flatInit(this); }
		FlatToggle(String t, javax.swing.Icon ic, boolean sel) { super(t, ic, sel); flatInit(this); }
	}

	private static void flatInit(javax.swing.AbstractButton b)
	{
		b.setFocusable(false);
		// FlatLaf's native borderless "toolbar button": no chrome until hover/press,
		// rounded highlight on rollover, accent fill when a toggle is selected.
		b.putClientProperty("JButton.buttonType", "toolBarButton");
		b.putClientProperty("JToggleButton.buttonType", "toolBarButton");
		b.setForeground(RB_TEXT);
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
	}

	/** Turn a button into a tall icon-over-text "large" ribbon button of uniform width. */
	private static void makeBig(javax.swing.AbstractButton b)
	{
		b.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
		b.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
		b.setPreferredSize(new Dimension(Math.max(56, b.getPreferredSize().width + 8), 62));
	}

	private JButton barButton(String text, String icon, String tip, java.awt.event.ActionListener a)
	{
		JButton b = new FlatButton("");
		if (text.isEmpty())
		{
			b.setText(icon);
			b.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
		}
		else
		{
			b.setText(bigLabelHtml(icon, text));
			makeBig(b);
		}
		b.setToolTipText(tip);
		b.addActionListener(a);
		return b;
	}

	private JToggleButton barToggle(String text, String tip)
	{
		JToggleButton b = new FlatToggle(text);
		b.setToolTipText(tip);
		return b;
	}

	private JCheckBox barCheck(String text, boolean sel, java.util.function.Consumer<Boolean> onChange)
	{
		JCheckBox c = new JCheckBox(text, sel);
		c.setFocusable(false);
		if (onChange != null)
		{
			c.addActionListener(e -> onChange.accept(c.isSelected()));
		}
		return c;
	}

	private void barGap(JToolBar bar)
	{
		bar.addSeparator(new Dimension(12, 0));
		bar.add(new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL)
		{
			@Override public Dimension getMaximumSize() { return new Dimension(1, 26); }
		});
		bar.addSeparator(new Dimension(12, 0));
	}

	private JComponent buildSidePanel()
	{
		sideTabs = new javax.swing.JTabbedPane();
		sideTabs.setPreferredSize(new Dimension(320, 100));
		sideTabs.addTab("Underlay", buildUnderlayTab());
		sideTabs.addTab("Overlay", buildOverlayTab());
		sideTabs.addTab("Shapes", buildShapesTab());
		sideTabs.addTab("Objects", buildObjectsTab());
		sideTabs.addTab("NPCs", buildNpcsTab());
		sideTabs.addTab("Models", buildModelsTab());
		sideTabs.addTab("Kit", buildKitTab());
		sideTabs.addTab("Map Icons", buildMapIconsTab());
		return sideTabs;
	}

	/** The always-visible "Selection / tile" box for the east panel (replaces the old Tools tab). */
	private JComponent buildInspectorBox()
	{
		inspector.setLayout(new BoxLayout(inspector, BoxLayout.Y_AXIS));
		inspector.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x3A3F47)));
		JLabel h = new JLabel(" Selection / tile");
		h.setFont(h.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		h.setForeground(new Color(0x8AB4F8));
		h.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
		wrap.add(h, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(inspector);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.setPreferredSize(new Dimension(320, 150));
		wrap.add(sp, BorderLayout.CENTER);
		return wrap;
	}

	// ---- palette tabs --------------------------------------------------

	private JComponent buildUnderlayTab()
	{
		JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
		grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		underlayGrid = grid;
		java.util.List<net.runelite.cache.definitions.UnderlayDefinition> list =
			new java.util.ArrayList<>(service.getUnderlays());
		list.sort(java.util.Comparator.comparingInt(net.runelite.cache.definitions.UnderlayDefinition::getId));
		for (net.runelite.cache.definitions.UnderlayDefinition u : list)
		{
			grid.add(paletteSwatch(u.getId(), u.getColor() & 0xFFFFFF, -1, "Underlay " + u.getId(),
				() -> parse(underlayField, -1),
				() -> { underlayField.setText(String.valueOf(u.getId())); setTool(Tool.UNDERLAY); grid.repaint(); }));
		}
		return paletteTab(grid, list.size() + " underlays — click to paint", 1);
	}

	private JComponent buildOverlayTab()
	{
		JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
		grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		overlayGrid = grid;
		// ALL overlays live here — both flat-colour overlays and texture overlays. A texture
		// overlay is drawn showing its texture image and marked with a small "T" badge in the
		// corner. Painting a texture this way selects a REAL existing overlay id (which exists in
		// both the client cache and the server's overlay.toml), so it renders correctly in-game —
		// unlike the old Textures tab, which minted brand-new overlay ids the server never had,
		// producing black tiles in-game.
		java.util.List<net.runelite.cache.definitions.OverlayDefinition> list =
			new java.util.ArrayList<>(service.getOverlays());
		list.sort(java.util.Comparator.comparingInt(net.runelite.cache.definitions.OverlayDefinition::getId));
		int texCount = 0;
		for (net.runelite.cache.definitions.OverlayDefinition o : list)
		{
			int tex = o.getTexture();
			int rgb = o.getRgbColor();
			// magenta primary (0xFF00FF) is the "transparent" marker — show the secondary instead.
			if ((rgb == 0 || rgb == 0xFF00FF) && o.getSecondaryRgbColor() != -1)
			{
				rgb = o.getSecondaryRgbColor();
			}
			if (tex != -1)
			{
				texCount++;
				// no meaningful flat colour on many texture overlays — fall back to the texture's
				// average colour so the swatch still has a sensible tint behind the "T".
				if (rgb == 0 || rgb == 0xFF00FF)
				{
					rgb = service.getTextureAverage(tex) & 0xFFFFFF;
				}
			}
			String tip = tex != -1
				? "Overlay " + o.getId() + " — texture " + tex + " (click to paint)"
				: "Overlay " + o.getId();
			grid.add(paletteSwatch(o.getId(), rgb & 0xFFFFFF, tex, tip,
				() -> parse(overlayField, -1),
				() -> { overlayField.setText(String.valueOf(o.getId())); setTool(Tool.OVERLAY); grid.repaint(); }));
		}

		// Tile shapes now live in their own "Shapes" side tab (buildShapesTab).
		return paletteTab(grid, list.size() + " overlays (" + texCount + " textured, marked T) — click to paint", 2);
	}

	/**
	 * Wraps a palette grid with a header + a button row: "Pick from map" (arms the
	 * eyedropper for this layer) and "Clear (none)" (sets the id to 0). {@code which}
	 * is 1 = underlay, 2 = overlay.
	 */
	private JComponent paletteTab(JComponent grid, String header, int which)
	{
		boolean under = which == 1;
		JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
		JButton pick = new JButton("Pick from map");
		pick.setToolTipText("Then click a tile to grab its " + (under ? "underlay" : "overlay"));
		pick.addActionListener(e ->
		{
			pendingPick = which;
			canvas2D.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
			canvas3D.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
			status.setText(" Pick " + (under ? "underlay" : "overlay") + ": click a tile on the map");
		});
		bar.add(pick);
		JButton clear = new JButton("Clear (none)");
		clear.setToolTipText("Set " + (under ? "underlay" : "overlay") + " to 0 — paint to remove it from tiles");
		clear.addActionListener(e ->
		{
			(under ? underlayField : overlayField).setText("0");
			setTool(under ? Tool.UNDERLAY : Tool.OVERLAY);
			repaintPalettes();
			status.setText(" " + (under ? "Underlay" : "Overlay") + " set to 0 (none) — paint to clear tiles");
		});
		bar.add(clear);

		JPanel north = new JPanel(new BorderLayout());
		JLabel h = new JLabel(" " + header);
		h.setBorder(BorderFactory.createEmptyBorder(3, 3, 0, 3));
		north.add(h, BorderLayout.NORTH);
		north.add(bar, BorderLayout.SOUTH);

		JPanel p = new JPanel(new BorderLayout());
		p.add(north, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(grid);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		p.add(sp, BorderLayout.CENTER);
		return p;
	}

	/**
	 * A palette colour cell that draws a white border when its id is the selected one.
	 * When {@code texId >= 0} the cell renders that texture's image and stamps a small "T"
	 * badge in the top-left corner so texture overlays are obvious among flat colours.
	 */
	private JComponent paletteSwatch(int id, int rgb, int texId, String tip,
		java.util.function.IntSupplier selectedId, Runnable onClick)
	{
		final Color color = new Color(rgb);
		final java.awt.image.BufferedImage texImg;
		if (texId >= 0)
		{
			int[] px = service.getTexturePixels(texId);
			if (px != null)
			{
				java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(
					MapEditorService.TEX_SIZE, MapEditorService.TEX_SIZE, java.awt.image.BufferedImage.TYPE_INT_RGB);
				im.setRGB(0, 0, MapEditorService.TEX_SIZE, MapEditorService.TEX_SIZE, px, 0, MapEditorService.TEX_SIZE);
				texImg = im;
			}
			else
			{
				texImg = null;
			}
		}
		else
		{
			texImg = null;
		}
		JComponent c = new JComponent()
		{
			@Override protected void paintComponent(Graphics g)
			{
				if (texImg != null)
				{
					g.drawImage(texImg, 0, 0, getWidth(), getHeight(), null);
				}
				else
				{
					g.setColor(color);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				if (texId >= 0)
				{
					// "T" badge — dark rounded chip with a light-blue letter, top-left corner.
					java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
					g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(new Color(0, 0, 0, 180));
					g2.fillRoundRect(0, 0, 15, 15, 4, 4);
					g2.setColor(new Color(0x4FC3F7));
					g2.setFont(getFont().deriveFont(java.awt.Font.BOLD, 12f));
					g2.drawString("T", 4, 12);
				}
				if (selectedId.getAsInt() == id)
				{
					g.setColor(Color.WHITE);
					((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(2.5f));
					g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
				}
				else
				{
					g.setColor(new Color(0, 0, 0, 90));
					g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
				}
			}
		};
		c.setPreferredSize(new Dimension(46, 34));
		c.setToolTipText(tip);
		c.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent e) { onClick.run(); }
		});
		return c;
	}

	private void repaintPalettes()
	{
		if (underlayGrid != null) { underlayGrid.repaint(); }
		if (overlayGrid != null) { overlayGrid.repaint(); }
	}

	/** One-shot eyedropper armed by a tab's "Pick from map" button. */
	private void pickLayer(int x, int y)
	{
		if (region == null)
		{
			return;
		}
		var tile = region.getTile(plane, x, y);
		if (tile == null)
		{
			return;
		}
		if (pendingPick == 1)
		{
			int un = tileIdToDef(tile.underlayId & 0xFFFF);
			underlayField.setText(String.valueOf(un));
			setTool(Tool.UNDERLAY);
			status.setText(" Picked underlay " + un + " — click to paint it");
		}
		else
		{
			int ov = tileIdToDef(tile.overlayId & 0xFFFF);
			overlayField.setText(String.valueOf(ov));
			overlayPathField.setText(String.valueOf(tile.overlayPath & 0xFF));
			overlayRotField.setText(String.valueOf(tile.overlayRotation & 3));
			setTool(Tool.OVERLAY);
			status.setText(" Picked overlay " + ov + " — click to paint it");
		}
		pendingPick = 0;
		canvas2D.setCursor(java.awt.Cursor.getDefaultCursor());
		canvas3D.setCursor(java.awt.Cursor.getDefaultCursor());
		repaintPalettes();
	}

	private JComponent buildObjectsTab()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JTextField search = new JTextField();
		// Row 1: search + location-type category filter (Wall / Door / Roof / …).
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.add(search, BorderLayout.CENTER);
		JComboBox<String> catCombo = new JComboBox<>(OBJ_CATEGORIES);
		catCombo.setToolTipText("Filter objects by category (derived from their location types)");
		catCombo.addActionListener(e ->
		{
			objectCatFilter = (String) catCombo.getSelectedItem();
			filterObjects(search.getText());
		});
		top.add(catCombo, BorderLayout.EAST);

		// Row 2: game-category id filter (opcode 61) — numeric, groups Jagex tagged (e.g. all trees).
		JTextField gameCatField = new JTextField();
		gameCatField.setToolTipText("Filter by game category id (opcode 61). Blank = all. Numeric only.");
		JPanel catRow = new JPanel(new BorderLayout(4, 0));
		JLabel gcl = new JLabel(" Game cat # ");
		gcl.setForeground(new Color(0xB4B8BE));
		catRow.add(gcl, BorderLayout.WEST);
		catRow.add(gameCatField, BorderLayout.CENTER);
		debounceSearch(gameCatField, s -> { objGameCatFilter = s.trim(); filterObjects(search.getText()); });

		JPanel north = new JPanel(new java.awt.GridLayout(2, 1, 0, 3));
		north.add(top);
		north.add(catRow);
		panel.add(north, BorderLayout.NORTH);

		JList<ObjEntry> list = new JList<>(objectListModel);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(40);
		list.setCellRenderer(new ObjectCell());
		panel.add(new JScrollPane(list), BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout());
		objectPreview.setHorizontalAlignment(JLabel.CENTER);
		objectPreview.setPreferredSize(new Dimension(160, 160));
		south.add(objectPreview, BorderLayout.CENTER);

		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

		// Per-model buttons: an object with more than one model (walls, roofs, multi-part
		// scenery, …) gets one button per model here. Clicking picks that model/type for
		// placement; the preview shows it. Rebuilt whenever an object is selected.
		typeButtonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 2));
		bottom.add(typeButtonRow);

		JPanel useRow = new JPanel(new BorderLayout(4, 0));
		JButton use = new JButton("Use for Place Object");
		// Arm whatever object is currently shown in the tab — set by a list click OR by clicking an
		// object on the map (showObjectInTab), both of which run through previewObjectInto.
		use.addActionListener(e -> useSelectedObject(lastPreviewedObj != null ? lastPreviewedObj : list.getSelectedValue()));
		useRow.add(use, BorderLayout.CENTER);
		// Rotation button — cycles 0..3 like before, but shows the compass letter (N/E/S/W).
		JButton rotB = new JButton("Rot " + rotLetter(0));
		rotB.setToolTipText("Placement rotation (or press R / X). Preview turns to show the facing before placing");
		rotB.addActionListener(e -> rotatePlacement());
		placeRotBtn = rotB;
		useRow.add(rotB, BorderLayout.EAST);
		bottom.add(useRow);
		south.add(bottom, BorderLayout.SOUTH);
		panel.add(south, BorderLayout.SOUTH);

		list.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				// Clicking an object now auto-arms it for placement (no separate "Use" click needed).
				useSelectedObject(list.getSelectedValue());
			}
		});
		debounceSearch(search, this::filterObjects);

		buildObjectIndex();
		filterObjects("");
		return panel;
	}

	private void buildObjectIndex()
	{
		if (allObjectEntries != null)
		{
			return;
		}
		allObjectEntries = new java.util.ArrayList<>();
		for (net.runelite.cache.definitions.ObjectDefinition d : service.getAllObjects())
		{
			String name = d.getName();
			// RuneLite uses the literal string "null" for unnamed objects; treat it as blank
			// so nameless custom objects (e.g. 46421) read as "(no name)" and are still id-searchable.
			if (name == null || name.equalsIgnoreCase("null"))
			{
				name = "";
			}
			allObjectEntries.add(new ObjEntry(d.getId(), name, objectCategory(d), d.getCategory()));
		}
		allObjectEntries.sort(java.util.Comparator.comparingInt(o -> o.id));
	}

	/**
	 * Attaches a debounced document listener: the {@code onChange} action runs once, ~180ms after
	 * the user stops typing, instead of on every keystroke. Keeps fast typing from firing a full
	 * (potentially thousands-of-rows) re-filter per character and freezing the UI.
	 */
	private void debounceSearch(JTextField field, java.util.function.Consumer<String> onChange)
	{
		javax.swing.Timer timer = new javax.swing.Timer(180, ev -> onChange.accept(field.getText()));
		timer.setRepeats(false);
		field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e) { timer.restart(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { timer.restart(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { timer.restart(); }
		});
	}

	private void filterObjects(String query)
	{
		if (allObjectEntries == null)
		{
			return;
		}
		String q = query.trim().toLowerCase();
		boolean catActive = objectCatFilter != null && !"All".equals(objectCatFilter);
		Integer gcFilter = null;
		if (objGameCatFilter != null && !objGameCatFilter.isEmpty())
		{
			try { gcFilter = Integer.parseInt(objGameCatFilter); } catch (NumberFormatException ignore) { }
		}
		java.util.List<ObjEntry> matches = new java.util.ArrayList<>();
		for (ObjEntry e : allObjectEntries)
		{
			if (catActive && !objectCatFilter.equals(e.cat))
			{
				continue;
			}
			if (gcFilter != null && e.gameCat != gcFilter)
			{
				continue;
			}
			boolean named = !e.name.isEmpty() && !e.name.equalsIgnoreCase("null");
			// Nameless objects display/search as "null" so they can be found by typing "null".
			String searchName = named ? e.name.toLowerCase() : "null";
			// Empty query: show named objects, OR everything in the chosen category / game-cat filter.
			boolean pass = q.isEmpty() ? (named || catActive || gcFilter != null)
				: (searchName.contains(q) || String.valueOf(e.id).contains(q));
			if (pass)
			{
				matches.add(e);
				if (matches.size() >= 5000)
				{
					break;
				}
			}
		}
		// One model update instead of thousands of per-row events — the key to smooth typing.
		objectListModel.clear();
		objectListModel.addAll(matches);
	}

	/**
	 * The "Kit" tab: a browser for multi-<b>type</b> objects — walls and roofs whose models are
	 * distinct location-type variants. Selecting one arms it and shows its Model 1/2/3 buttons so
	 * you can place each variant. Multi-part objects (e.g. the Portal) are single objects and live
	 * in the normal Objects tab, not here.
	 */
	private JComponent buildKitTab()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JTextField search = new JTextField();
		search.setToolTipText("Search wall/roof kits by name or id (type null for unnamed)");
		panel.add(search, BorderLayout.NORTH);

		// Top: the kit-object list. Bottom: a scrollable grid of that object's model variants,
		// each a rendered thumbnail you click to arm. A vertical split lets you size the two.
		JList<ObjEntry> list = new JList<>(kitListModel);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(40);
		list.setCellRenderer(new ObjectCell()); // thumbnail + name + type rows, like the Objects tab
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setBorder(BorderFactory.createEmptyBorder());

		JPanel variants = new JPanel(new BorderLayout(4, 4));

		JPanel vHeader = new JPanel(new BorderLayout());
		vHeader.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		kitVariantTitle = new JLabel("Select a kit");
		kitVariantTitle.setFont(kitVariantTitle.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		kitVariantTitle.setForeground(new Color(0x8AB4F8));
		vHeader.add(kitVariantTitle, BorderLayout.WEST);
		kitRotBtn = new JButton("Rot " + rotLetter(parse(objectRotField, 0)));
		kitRotBtn.setToolTipText("Placement rotation (or press R / X)");
		kitRotBtn.setFocusable(false);
		kitRotBtn.setMargin(new java.awt.Insets(1, 8, 1, 8));
		kitRotBtn.addActionListener(e -> rotatePlacement());
		vHeader.add(kitRotBtn, BorderLayout.EAST);
		variants.add(vHeader, BorderLayout.NORTH);

		kitVariantGrid = new JPanel(new WrapLayout(java.awt.FlowLayout.LEFT, 6, 6));
		JScrollPane gridScroll = new JScrollPane(kitVariantGrid,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		gridScroll.getVerticalScrollBar().setUnitIncrement(16);
		gridScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x3A3F47)));
		variants.add(gridScroll, BorderLayout.CENTER);

		JLabel hint = new JLabel("Click a model to arm it · rotate with R/X · click map to place");
		hint.setFont(hint.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
		hint.setForeground(new Color(0x9AA0A6));
		hint.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
		variants.add(hint, BorderLayout.SOUTH);

		javax.swing.JSplitPane split = new javax.swing.JSplitPane(
			javax.swing.JSplitPane.VERTICAL_SPLIT, listScroll, variants);
		split.setResizeWeight(0.42);
		split.setBorder(null);
		split.setDividerSize(6);
		panel.add(split, BorderLayout.CENTER);

		list.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				armKitObject(list.getSelectedValue());
			}
		});
		debounceSearch(search, this::filterKit);

		buildKitIndex();
		filterKit("");
		return panel;
	}

	/** Arms a kit object for placement and rebuilds its variant thumbnail grid. */
	private void armKitObject(ObjEntry e)
	{
		if (e == null)
		{
			if (kitVariantTitle != null) { kitVariantTitle.setText("Select a kit"); }
			if (kitVariantGrid != null) { kitVariantGrid.removeAll(); kitVariantGrid.revalidate(); kitVariantGrid.repaint(); }
			return;
		}
		objectIdField.setText(String.valueOf(e.id));
		int t = MapRenderer.naturalType(service.getObject(e.id));
		objectTypeField.setText(String.valueOf(t));
		setTool(Tool.PLACE_OBJECT);
		previewObjectInto(e, kitPreview);
		rebuildKitVariants(e);
		status.setText(" Kit " + e.id + "  " + (e.name.isEmpty() ? "null" : e.name)
			+ " — pick a model below");
	}

	/**
	 * Rebuilds the variant grid for the selected kit: one rendered thumbnail tile per model/type,
	 * wrapping to as many rows as needed inside the scroll pane (so a 10-model roof shows all ten).
	 * Clicking a tile arms that specific model/type for placement.
	 */
	private void rebuildKitVariants(ObjEntry sel)
	{
		if (kitVariantGrid == null)
		{
			return;
		}
		kitVariantGrid.removeAll();
		kitTypeButtonList.clear();
		ObjectDefinition def = sel != null ? service.getObject(sel.id) : null;
		int[] models = def != null ? def.getObjectModels() : null;
		int[] types = def != null ? def.getObjectTypes() : null;
		if (def == null || models == null || types == null)
		{
			if (kitVariantTitle != null) { kitVariantTitle.setText("No models"); }
			kitVariantGrid.revalidate();
			kitVariantGrid.repaint();
			return;
		}
		if (kitVariantTitle != null)
		{
			kitVariantTitle.setText(models.length + (models.length == 1 ? " model" : " models"));
		}
		int curType = parse(objectTypeField, 10);
		java.util.List<javax.swing.ImageIcon> thumbs = kitThumbsFor(sel.id, models, types);
		for (int i = 0; i < models.length; i++)
		{
			final int type = i < types.length ? types[i] : i;
			final int modelId = models[i];
			String shape = wallShapeName(type);
			JButton tile = new JButton("<html><center>Model " + (i + 1)
				+ (shape != null ? "<br><font size=2 color='#9AA0A6'>" + shape + "</font>" : "")
				+ "</center></html>");
			javax.swing.ImageIcon ic = i < thumbs.size() ? thumbs.get(i) : null;
			if (ic != null) { tile.setIcon(ic); }
			tile.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
			tile.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
			tile.setPreferredSize(new Dimension(86, 104));
			tile.setFocusable(false);
			tile.setMargin(new java.awt.Insets(2, 2, 2, 2));
			tile.setToolTipText("Model " + modelId + " · type " + type
				+ (shape != null ? " (" + shape + ")" : ""));
			tile.putClientProperty("kitType", type);
			tile.addActionListener(ev ->
			{
				objectIdField.setText(String.valueOf(sel.id));
				objectTypeField.setText(String.valueOf(type));
				setTool(Tool.PLACE_OBJECT);
				highlightKitTiles(type);
				previewObjectInto(sel, kitPreview);
				status.setText(" Place " + (sel.name.isEmpty() ? "obj " + sel.id : sel.name)
					+ " — model " + modelId + " (type " + type + ") — R/X rotate, click map to place");
			});
			kitTypeButtonList.add(tile);
			kitVariantGrid.add(tile);
		}
		highlightKitTiles(curType);
		kitVariantGrid.revalidate();
		kitVariantGrid.repaint();
	}

	/** Renders (and caches) the per-variant thumbnails for a kit object. */
	private java.util.List<javax.swing.ImageIcon> kitThumbsFor(int id, int[] models, int[] types)
	{
		java.util.List<javax.swing.ImageIcon> cached = kitThumbCache.get(id);
		if (cached != null)
		{
			return cached;
		}
		java.util.List<javax.swing.ImageIcon> out = new java.util.ArrayList<>();
		for (int i = 0; i < models.length; i++)
		{
			int type = i < types.length ? types[i] : i;
			try
			{
				double[] info = new double[2];
				Renderer3D.Scene s = sceneBuilder.buildModelPreview(id, 0, type, info);
				out.add(new javax.swing.ImageIcon(
					renderPreviewImage(s, info).getScaledInstance(64, 64, java.awt.Image.SCALE_SMOOTH)));
			}
			catch (Exception ex)
			{
				out.add(null);
			}
		}
		kitThumbCache.put(id, out);
		return out;
	}

	/** Highlights the variant tile matching the given placement type. */
	private void highlightKitTiles(int selType)
	{
		for (JButton b : kitTypeButtonList)
		{
			Object tt = b.getClientProperty("kitType");
			boolean on = tt instanceof Integer && (Integer) tt == selType;
			b.setBorder(on
				? BorderFactory.createLineBorder(new Color(0xFFCC33), 2)
				: BorderFactory.createLineBorder(new Color(0x3C3F41), 1));
		}
	}

	/**
	 * Builds the list of true "kit" objects — objects with more than one <b>type</b>
	 * ({@code objectTypes != null && length > 1}), i.e. walls and roofs where each model is a
	 * distinct location-type variant you pick between (Model 1/2/3…).
	 * <p>
	 * Multi-<b>part</b> objects ({@code objectTypes == null} but several models, e.g. the Portal)
	 * are NOT kits — their models are all one object placed together as scenery. Those are left
	 * out of this tab and browsed from the normal Objects tab instead.
	 */
	private void buildKitIndex()
	{
		if (kitEntries != null)
		{
			return;
		}
		buildObjectIndex();
		kitEntries = new java.util.ArrayList<>();
		for (ObjEntry e : allObjectEntries)
		{
			net.runelite.cache.definitions.ObjectDefinition d = service.getObject(e.id);
			int[] types = d != null ? d.getObjectTypes() : null;
			if (types != null && types.length > 1)
			{
				kitEntries.add(e);
			}
		}
	}

	private void filterKit(String query)
	{
		if (kitEntries == null)
		{
			return;
		}
		String q = query.trim().toLowerCase();
		java.util.List<ObjEntry> matches = new java.util.ArrayList<>();
		for (ObjEntry e : kitEntries)
		{
			// Show all multi-model objects (named and unnamed) so nothing is hidden here.
			String searchName = e.name.isEmpty() ? "null" : e.name.toLowerCase();
			if (q.isEmpty() || searchName.contains(q) || String.valueOf(e.id).contains(q))
			{
				matches.add(e);
				if (matches.size() >= 5000)
				{
					break;
				}
			}
		}
		kitListModel.clear();
		kitListModel.addAll(matches);
	}

	private void useSelectedObject(ObjEntry e)
	{
		armObject(e, objectPreview, typeButtonRow, typeButtonList);
	}

	/**
	 * The "Map Icons" tab: a browser of every object that carries a {@code map_icon} (mapAreaId) —
	 * the minimap-icon markers (well, portal, altar, minigame, achievements, …). Each row shows the
	 * icon picture so you can find one visually without knowing its (usually null) name/id. Clicking
	 * arms it for placement as ground-decoration (type 22), exactly how the server spawns its icons.
	 */
	private JComponent buildMapIconsTab()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JTextField search = new JTextField();
		search.setToolTipText("Search minimap-icon objects by name, id, or map_icon number");
		panel.add(search, BorderLayout.NORTH);

		JList<ObjEntry> list = new JList<>(mapIconListModel);
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new MapIconCell());
		list.setFixedCellHeight(22);
		panel.add(new JScrollPane(list), BorderLayout.CENTER);

		JLabel hint = new JLabel("<html>Click an icon to arm it, then click the map to place.<br>"
			+ "Placed as ground-decoration (type 22), like the server's icon spawns.</html>");
		hint.setFont(hint.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
		hint.setForeground(new Color(0x9AA0A6));
		hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
		panel.add(hint, BorderLayout.SOUTH);

		list.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				armMapIcon(list.getSelectedValue());
			}
		});
		debounceSearch(search, this::filterMapIcons);

		buildMapIconIndex();
		filterMapIcons("");
		return panel;
	}

	/** Builds the list of objects that carry a map_icon (mapAreaId != -1). */
	private void buildMapIconIndex()
	{
		if (mapIconEntries != null)
		{
			return;
		}
		buildObjectIndex();
		mapIconEntries = new java.util.ArrayList<>();
		for (ObjEntry e : allObjectEntries)
		{
			ObjectDefinition d = service.getObject(e.id);
			if (d != null && d.getMapAreaId() >= 0)
			{
				mapIconEntries.add(e);
			}
		}
	}

	private void filterMapIcons(String query)
	{
		if (mapIconEntries == null)
		{
			return;
		}
		String q = query.trim().toLowerCase();
		java.util.List<ObjEntry> matches = new java.util.ArrayList<>();
		for (ObjEntry e : mapIconEntries)
		{
			ObjectDefinition d = service.getObject(e.id);
			int mi = d != null ? d.getMapAreaId() : -1;
			String searchName = e.name.isEmpty() ? "null" : e.name.toLowerCase();
			if (q.isEmpty() || searchName.contains(q) || String.valueOf(e.id).contains(q)
				|| String.valueOf(mi).equals(q))
			{
				matches.add(e);
				if (matches.size() >= 5000)
				{
					break;
				}
			}
		}
		mapIconListModel.clear();
		mapIconListModel.addAll(matches);
	}

	/** Small cached icon image for a map_icon (mapAreaId), scaled for a list row. */
	private javax.swing.ImageIcon mapIconThumb(int mapAreaId)
	{
		if (mapAreaId < 0)
		{
			return null;
		}
		if (mapIconThumbCache.containsKey(mapAreaId))
		{
			return mapIconThumbCache.get(mapAreaId);
		}
		javax.swing.ImageIcon ic = null;
		java.awt.image.BufferedImage img = service.getWorldMapIcon(mapAreaId);
		if (img != null)
		{
			int h = 18;
			int w = Math.max(1, img.getWidth() * h / Math.max(1, img.getHeight()));
			ic = new javax.swing.ImageIcon(img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH));
		}
		mapIconThumbCache.put(mapAreaId, ic);
		return ic;
	}

	/** List cell that shows the map_icon picture next to the object id/name. */
	private final class MapIconCell extends javax.swing.DefaultListCellRenderer
	{
		@Override public java.awt.Component getListCellRendererComponent(
			JList<?> l, Object value, int index, boolean sel, boolean focus)
		{
			JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, sel, focus);
			if (value instanceof ObjEntry)
			{
				ObjEntry e = (ObjEntry) value;
				ObjectDefinition d = service.getObject(e.id);
				int mi = d != null ? d.getMapAreaId() : -1;
				c.setText(e.id + "   " + (e.name.isEmpty() ? "null" : e.name) + "   [icon " + mi + "]");
				c.setIcon(mapIconThumb(mi));
				c.setIconTextGap(8);
			}
			return c;
		}
	}

	/** Arms a map-icon object for placement as ground-decoration (type 22, like the server). */
	private void armMapIcon(ObjEntry e)
	{
		if (e == null)
		{
			return;
		}
		ObjectDefinition d = service.getObject(e.id);
		int mi = d != null ? d.getMapAreaId() : -1;
		objectIdField.setText(String.valueOf(e.id));
		objectTypeField.setText("22"); // ground decoration — how ClientObj spawns icon markers
		setTool(Tool.PLACE_OBJECT);
		previewObjectInto(e, objectPreview);
		status.setText(" Place map icon — object " + e.id + " (icon " + mi + ") — click a tile to place");
	}

	/**
	 * Arms an object for placement and refreshes a given preview label + per-model button row.
	 * Used by both the Objects tab and the Kit tab (each has its own preview/buttons).
	 */
	private void armObject(ObjEntry e, JLabel preview, JPanel btnRow, java.util.List<JButton> btnList)
	{
		if (e == null)
		{
			return;
		}
		objectIdField.setText(String.valueOf(e.id));
		// Auto-pick the correct placement type: walls place as type 0 (so they
		// render/behave as walls), scenery as 10.
		int t = MapRenderer.naturalType(service.getObject(e.id));
		objectTypeField.setText(String.valueOf(t));
		setTool(Tool.PLACE_OBJECT);
		previewObjectInto(e, preview);
		rebuildTypeButtonsInto(e, btnRow, btnList, preview);
		status.setText(" Place Object " + e.id + "  " + (e.name.isEmpty() ? "null" : e.name)
			+ (t <= 3 || t == 9 ? "  (wall — type " + t + ")" : ""));
	}

	private void rebuildTypeButtons(ObjEntry sel)
	{
		rebuildTypeButtonsInto(sel, typeButtonRow, typeButtonList, objectPreview);
	}

	/**
	 * Rebuilds the per-model button row for the selected object into the given row/list, with
	 * clicks refreshing the given preview. Objects with more than one model (walls, roofs,
	 * multi-part scenery) get one "Model N" button per model; single-model objects show none.
	 */
	private void rebuildTypeButtonsInto(ObjEntry sel, JPanel row, java.util.List<JButton> btnList, JLabel preview)
	{
		if (row == null)
		{
			return;
		}
		row.removeAll();
		btnList.clear();
		ObjectDefinition def = sel != null ? service.getObject(sel.id) : null;
		int[] models = def != null ? def.getObjectModels() : null;
		int[] types = def != null ? def.getObjectTypes() : null;
		// Only genuine multi-TYPE objects (walls: types like [0,1,2,3,9], one model per type) get
		// per-type buttons. Objects with types==null are multi-PART — all their models render
		// together as one scenery object. They must NOT get per-model buttons, because placing a
		// "part" as type 0/1 would write a WALL type and the object vanishes in-game.
		if (def == null || models == null || models.length <= 1 || types == null)
		{
			row.revalidate();
			row.repaint();
			return;
		}
		int curType = parse(objectTypeField, 10);
		row.add(new JLabel("Models:"));
		for (int i = 0; i < models.length; i++)
		{
			final int type = types != null && i < types.length ? types[i] : i;
			final int modelId = models[i];
			JButton b = new JButton("Model " + (i + 1));
			b.setMargin(new java.awt.Insets(1, 5, 1, 5));
			b.setFocusable(false);
			String shape = wallShapeName(type);
			b.setToolTipText("Model " + modelId + " · type " + type + (shape != null ? " (" + shape + ")" : ""));
			if (type == curType)
			{
				b.setBorder(BorderFactory.createLineBorder(new Color(0xFFCC33), 2));
			}
			b.addActionListener(ev ->
			{
				objectIdField.setText(String.valueOf(sel.id));
				objectTypeField.setText(String.valueOf(type));
				setTool(Tool.PLACE_OBJECT);
				for (JButton other : btnList)
				{
					other.setBorder(other == b ? BorderFactory.createLineBorder(new Color(0xFFCC33), 2) : null);
				}
				previewObjectInto(sel, preview);
				status.setText(" Place " + (sel.name.isEmpty() ? "obj " + sel.id : sel.name)
					+ " — model " + modelId + " (type " + type + ") — rotate with R/X, click to place");
			});
			btnList.add(b);
			row.add(b);
		}
		row.revalidate();
		row.repaint();
	}

	/** Human name for a wall-placement type, or null if not a wall shape (for tooltips). */
	private static String wallShapeName(int type)
	{
		switch (type)
		{
			case 0: return "straight";
			case 1: return "corner";
			case 2: return "L-corner";
			case 3: return "square";
			case 9: return "diagonal";
			default: return null;
		}
	}

	/** Compass letter for a placement rotation: rot 0=E, 1=S, 2=W, 3=N. */
	private static String rotLetter(int rot)
	{
		return String.valueOf("ESWN".charAt(rot & 3));
	}

	/** Cycle the placement rotation 90° (button + R/X key), updating the labels and previews. */
	private void rotatePlacement()
	{
		int rot = (parse(objectRotField, 0) + 1) & 3;
		objectRotField.setText(String.valueOf(rot));
		String lbl = "Rot " + rotLetter(rot);
		if (placeRotBtn != null) { placeRotBtn.setText(lbl); }
		if (kitRotBtn != null) { kitRotBtn.setText(lbl); }
		// Refresh both tab previews so whichever is visible shows the new facing.
		previewObjectInto(lastPreviewedObj, objectPreview);
		previewObjectInto(lastPreviewedObj, kitPreview);
	}

	private void previewObject(ObjEntry e)
	{
		previewObjectInto(e, objectPreview);
	}

	/** Small cached thumbnail for an Objects-tab row (rendered lazily on first request). */
	private javax.swing.Icon objThumb(int id)
	{
		if (objThumbCache.containsKey(id)) { return objThumbCache.get(id); }
		javax.swing.Icon ic = null;
		net.runelite.cache.definitions.ObjectDefinition def = service.getObject(id);
		int[] models = def != null ? def.getObjectModels() : null;
		try
		{
			if (models != null && models.length > 0)
			{
				// Has a 3D model — render it.
				double[] info = new double[2];
				Renderer3D.Scene s = sceneBuilder.buildModelPreview(id, 0, MapRenderer.naturalType(def), info);
				Renderer3D.Camera cam = new Renderer3D.Camera();
				cam.cx = 0; cam.cz = 0; cam.cy = info[1];
				cam.distance = Math.max(300, info[0] * 2.5);
				cam.zoom = 1.15; cam.yaw = 0.7; cam.pitch = 0.5;
				java.awt.image.BufferedImage shared = thumbRenderer.render(s, cam, texSource);
				java.awt.image.BufferedImage copy = new java.awt.image.BufferedImage(
					shared.getWidth(), shared.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics g = copy.getGraphics();
				g.drawImage(shared, 0, 0, null);
				g.dispose();
				ic = new javax.swing.ImageIcon(copy);
			}
			else if (def != null)
			{
				// No model (e.g. a bank/altar map_icon marker) — show its minimap sprite instead.
				java.awt.image.BufferedImage sprite = null;
				if (def.getMapAreaId() >= 0) { sprite = service.getWorldMapIcon(def.getMapAreaId()); }
				if (sprite == null && def.getMapSceneID() >= 0) { sprite = service.getMapSceneImage(def.getMapSceneID()); }
				if (sprite != null) { ic = spriteThumb(sprite); }
			}
		}
		catch (Exception ex)
		{
			ic = null;
		}
		objThumbCache.put(id, ic); // cache misses too, so a failed render isn't retried every repaint
		return ic;
	}

	/** Fits a small minimap sprite (with transparency) into a 34×34 thumbnail, centred. */
	private javax.swing.Icon spriteThumb(java.awt.image.BufferedImage sprite)
	{
		java.awt.image.BufferedImage copy = new java.awt.image.BufferedImage(34, 34, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = copy.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		int sw = sprite.getWidth(), sh = sprite.getHeight();
		double scale = Math.min(32.0 / sw, 32.0 / sh);
		if (scale > 2) { scale = 2; } // don't blow tiny icons up too far
		int dw = Math.max(1, (int) (sw * scale)), dh = Math.max(1, (int) (sh * scale));
		g.drawImage(sprite, (34 - dw) / 2, (34 - dh) / 2, dw, dh, null);
		g.dispose();
		return new javax.swing.ImageIcon(copy);
	}

	/** Objects-tab list row: small model thumbnail + name + id · category · game-cat. */
	private final class ObjectCell extends javax.swing.DefaultListCellRenderer
	{
		@Override
		public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean sel, boolean focus)
		{
			super.getListCellRendererComponent(list, value, index, sel, focus);
			if (value instanceof ObjEntry)
			{
				ObjEntry e = (ObjEntry) value;
				String nm = e.name.isEmpty() ? "(no name)" : e.name
					.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
				String sub = "id " + e.id + " · " + e.cat + (e.gameCat > 0 ? " · cat " + e.gameCat : "");
				setText("<html>" + nm + "<br><span style='font-size:9px;color:#9AA0A6'>" + sub + "</span></html>");
				setIcon(objThumb(e.id));
				setIconTextGap(8);
				setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			}
			return this;
		}
	}

	private void previewObjectInto(ObjEntry e, JLabel target)
	{
		if (e == null)
		{
			target.setIcon(null);
			return;
		}
		lastPreviewedObj = e;
		try
		{
			double[] info = new double[2];
			Renderer3D.Scene s = sceneBuilder.buildModelPreview(e.id, parse(objectRotField, 0), parse(objectTypeField, 10), info);
			target.setIcon(renderPreview(s, info));
			target.setText(null);
		}
		catch (Exception ex)
		{
			target.setIcon(null);
			target.setText("no preview");
		}
	}

	private javax.swing.ImageIcon renderPreview(Renderer3D.Scene s, double[] info)
	{
		return new javax.swing.ImageIcon(renderPreviewImage(s, info));
	}

	private java.awt.image.BufferedImage renderPreviewImage(Renderer3D.Scene s, double[] info)
	{
		Renderer3D.Camera cam = new Renderer3D.Camera();
		cam.cx = 0; cam.cz = 0; cam.cy = info[1];
		cam.distance = Math.max(300, info[0] * 2.5);
		cam.zoom = 1.15; cam.yaw = 0.7; cam.pitch = 0.5;
		// The renderer now reuses one backing buffer per instance, so each thumbnail must be
		// copied out — otherwise every preview icon would alias the last-rendered scene.
		java.awt.image.BufferedImage shared = previewRenderer.render(s, cam, texSource);
		java.awt.image.BufferedImage copy = new java.awt.image.BufferedImage(
			shared.getWidth(), shared.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics g = copy.getGraphics();
		g.drawImage(shared, 0, 0, null);
		g.dispose();
		return copy;
	}

	/** Build (and cache) the model thumbnail for an object/NPC hotbar slot. */
	private void rebuildHotIcon(int idx)
	{
		hotIcons[idx] = null;
		HotSlot s = hotSlots[idx];
		if (s == null || s.tool == null)
		{
			return;
		}
		try
		{
			double[] info = new double[2];
			Renderer3D.Scene scene;
			if ("PLACE_OBJECT".equals(s.tool))
			{
				int id = toInt(s.objId, -1);
				if (id < 0)
				{
					return;
				}
				scene = sceneBuilder.buildModelPreview(id, toInt(s.objRot, 0), info);
			}
			else if ("PLACE_NPC".equals(s.tool))
			{
				int id = toInt(s.npcId, -1);
				if (id < 0)
				{
					return;
				}
				scene = sceneBuilder.buildNpcPreview(id, info);
			}
			else
			{
				return;
			}
			hotIcons[idx] = renderPreviewImage(scene, info);
		}
		catch (Exception ignored)
		{
		}
	}

	// ---- NPC viewer ----------------------------------------------------

	private JComponent buildNpcsTab()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		JTextField search = new JTextField();
		panel.add(search, BorderLayout.NORTH);

		javax.swing.DefaultListModel<ObjEntry> model = new javax.swing.DefaultListModel<>();
		JList<ObjEntry> list = new JList<>(model);
		panel.add(new JScrollPane(list), BorderLayout.CENTER);

		JLabel preview = new JLabel("select an NPC", JLabel.CENTER);
		preview.setPreferredSize(new Dimension(160, 170));
		JPanel south = new JPanel(new BorderLayout());
		south.add(preview, BorderLayout.CENTER);
		JPanel placeRow = new JPanel(new BorderLayout(4, 0));
		JButton place = new JButton("Use for Place NPC");
		placeRow.add(place, BorderLayout.CENTER);
		javax.swing.JComboBox<String> dirBox =
			new javax.swing.JComboBox<>(new String[]{"Face S", "Face W", "Face N", "Face E"});
		dirBox.setToolTipText("Direction the placed NPC faces (saved to the spawn json)");
		dirBox.addActionListener(e -> placeNpcDir = dirBox.getSelectedIndex());
		placeRow.add(dirBox, BorderLayout.EAST);
		south.add(placeRow, BorderLayout.SOUTH);
		panel.add(south, BorderLayout.SOUTH);

		java.util.List<ObjEntry> all = new java.util.ArrayList<>();
		for (net.runelite.cache.definitions.NpcDefinition d : service.getAllNpcs())
		{
			all.add(new ObjEntry(d.getId(), d.getName() == null ? "" : d.getName()));
		}
		all.sort(java.util.Comparator.comparingInt(o -> o.id));

		Runnable filter = () ->
		{
			String q = search.getText().trim().toLowerCase();
			model.clear();
			int shown = 0;
			for (ObjEntry e : all)
			{
				boolean named = !e.name.isEmpty() && !e.name.equalsIgnoreCase("null");
				if (q.isEmpty() ? named : (e.name.toLowerCase().contains(q) || String.valueOf(e.id).contains(q)))
				{
					model.addElement(e);
					if (++shown >= 5000) { break; }
				}
			}
		};
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
		});
		list.addListSelectionListener(e ->
		{
			ObjEntry sel = list.getSelectedValue();
			if (!e.getValueIsAdjusting() && sel != null)
			{
				try
				{
					double[] info = new double[2];
					preview.setIcon(renderPreview(sceneBuilder.buildNpcPreview(sel.id, info), info));
					preview.setText(null);
				}
				catch (Exception ex) { preview.setIcon(null); preview.setText("no preview"); }
			}
		});
		place.addActionListener(e ->
		{
			ObjEntry sel = list.getSelectedValue();
			if (sel != null)
			{
				placeNpcId = sel.id;
				placeNpcName = sel.name;
				setTool(Tool.PLACE_NPC);
				status.setText(" Place NPC " + sel.id + "  " + sel.name + " — click tiles (saved to spawn json on Save)");
			}
		});
		list.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent me)
			{
				if (me.getClickCount() == 2) { place.doClick(); }
			}
		});
		filter.run();
		return panel;
	}

	// ---- model viewer --------------------------------------------------

	private JComponent buildModelsTab()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		javax.swing.JSpinner idSpin = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 100000, 1));
		JLabel preview = new JLabel("enter a model id", JLabel.CENTER);
		preview.setPreferredSize(new Dimension(160, 200));

		Runnable show = () ->
		{
			int id = (Integer) idSpin.getValue();
			try
			{
				double[] info = new double[2];
				Renderer3D.Scene s = sceneBuilder.buildModelPreviewById(id, info);
				if (s.size() == 0) { preview.setIcon(null); preview.setText("model " + id + " empty/missing"); }
				else { preview.setIcon(renderPreview(s, info)); preview.setText(null); }
			}
			catch (Exception ex) { preview.setIcon(null); preview.setText("no preview"); }
		};
		idSpin.addChangeListener(e -> show.run());

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.add(new JLabel("Model id "), BorderLayout.WEST);
		top.add(idSpin, BorderLayout.CENTER);
		JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		JButton prev = new JButton("◀ Prev");
		prev.addActionListener(e -> idSpin.setValue(Math.max(0, (Integer) idSpin.getValue() - 1)));
		JButton next = new JButton("Next ▶");
		next.addActionListener(e -> idSpin.setValue((Integer) idSpin.getValue() + 1));
		nav.add(prev);
		nav.add(next);

		JPanel north = new JPanel(new BorderLayout());
		north.add(top, BorderLayout.NORTH);
		north.add(nav, BorderLayout.SOUTH);
		panel.add(north, BorderLayout.NORTH);
		panel.add(preview, BorderLayout.CENTER);
		show.run();
		return panel;
	}

	// ---- element hotbar --------------------------------------------------

	private JComponent buildHotbar()
	{
		JPanel bar = new JPanel(new BorderLayout(4, 0));
		bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		bar.setToolTipText("Element slots — left-click to use, right-click to assign the current tool");

		JPanel grid = new JPanel(new GridLayout(2, HOT_KEYS.length / 2, 3, 3));
		for (int i = 0; i < HOT_KEYS.length; i++)
		{
			final int idx = i;
			JComponent slot = new JComponent()
			{
				@Override protected void paintComponent(Graphics g)
				{
					paintHotSlot((java.awt.Graphics2D) g, idx, getWidth(), getHeight());
				}
			};
			slot.setPreferredSize(new Dimension(60, 62));
			slot.addMouseListener(new MouseAdapter()
			{
				@Override public void mousePressed(MouseEvent e)
				{
					if (javax.swing.SwingUtilities.isRightMouseButton(e))
					{
						showHotSlotMenu(e, idx);
					}
					else
					{
						applyHotSlot(idx);
					}
				}
			});
			hotComps[i] = slot;
			grid.add(slot);
		}
		// Grid stretches to fill the bottom (a strut in the south panel keeps it from
		// running under the right-hand panel).
		bar.add(grid, BorderLayout.CENTER);
		return bar;
	}

	private void paintHotSlot(java.awt.Graphics2D g, int idx, int w, int h)
	{
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		HotSlot s = hotSlots[idx];
		g.setColor(new Color(0x2B2F33));
		g.fillRect(0, 0, w, h);
		int ch = h - 12; // content area; bottom 12px is the name band

		if (s != null && s.tool != null)
		{
			try
			{
				Tool t = Tool.valueOf(s.tool);
				switch (t)
				{
					case UNDERLAY:
					{
						var u = service.getUnderlay(toInt(s.underlay, 0));
						g.setColor(u != null ? new Color(u.getColor() & 0xFFFFFF) : Color.DARK_GRAY);
						g.fillRect(2, 2, w - 4, ch - 4);
						break;
					}
					case OVERLAY:
					{
						var o = service.getOverlay(toInt(s.overlay, 0));
						int rgb = 0x555555;
						if (o != null)
						{
							rgb = o.getRgbColor();
							if (rgb == 0 && o.getSecondaryRgbColor() != -1) { rgb = o.getSecondaryRgbColor(); }
							if (rgb == 0 && o.getTexture() != -1) { rgb = service.getTextureAverage(o.getTexture()); }
						}
						int path = toInt(s.path, 0);
						byte[] mask = path == 0 ? null : MapRenderer.maskFor(path, toInt(s.rot, 0));
						g.setColor(new Color(rgb & 0xFFFFFF));
						if (mask == null)
						{
							g.fillRect(2, 2, w - 4, ch - 4);
						}
						else
						{
							int sc = MapRenderer.SHAPE_SCALE;
							double sx = (w - 4) / (double) sc, sy = (ch - 4) / (double) sc;
							int mi = 0;
							for (int iy = 0; iy < sc; iy++)
							{
								for (int ix = 0; ix < sc; ix++)
								{
									if (mask[mi++] != 0)
									{
										g.fillRect(2 + (int) (ix * sx), 2 + (int) (iy * sy),
											(int) Math.ceil(sx), (int) Math.ceil(sy));
									}
								}
							}
						}
						break;
					}
					case PLACE_OBJECT:
					case PLACE_NPC:
					{
						java.awt.image.BufferedImage ic = hotIcons[idx];
						if (ic != null)
						{
							g.drawImage(ic, 2, 2, w - 4, ch - 4, null);
						}
						else
						{
							boolean npc = t == Tool.PLACE_NPC;
							g.setColor(npc ? new Color(0xC58AE0) : new Color(0x8AE08A));
							g.drawString(npc ? "N" : "O", w / 2 - 4, ch / 2 + 5);
						}
						break;
					}
					case HEIGHT:
						g.setColor(new Color(0xE0C05A));
						g.fillPolygon(new int[]{w / 2, w / 2 - 6, w / 2 + 6},
							new int[]{4, ch / 2, ch / 2}, 3);
						g.fillPolygon(new int[]{w / 2, w / 2 - 6, w / 2 + 6},
							new int[]{ch - 4, ch / 2 + 2, ch / 2 + 2}, 3);
						break;
					case SETTINGS:
						g.setColor(new Color(0xE07A5A));
						g.fillRect(w / 2 - 5, 4, 3, ch - 8);
						g.fillRect(w / 2 - 3, 5, 9, (ch - 8) / 2);
						break;
					case DELETE_OBJECT:
						g.setColor(new Color(0xE05050));
						g.drawString("✕", w / 2 - 4, ch / 2 + 5);
						break;
					default:
						g.setColor(Color.LIGHT_GRAY);
						g.drawString("➤", w / 2 - 5, ch / 2 + 5);
				}
			}
			catch (RuntimeException ignored)
			{
			}
		}

		// Name band (truncated to fit the slot width).
		String name = hotSlotName(s);
		if (!name.isEmpty())
		{
			g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 9f));
			java.awt.FontMetrics fm = g.getFontMetrics();
			String txt = name;
			while (txt.length() > 1 && fm.stringWidth(txt) > w - 4)
			{
				txt = txt.substring(0, txt.length() - 1);
			}
			g.setColor(new Color(0xCFD3D7));
			g.drawString(txt, 2, h - 3);
		}

		// key label + border
		g.setColor(new Color(255, 255, 255, 200));
		g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 10f));
		g.drawString(HOT_KEYS[idx], 3, 11);
		g.setColor(idx == activeHotSlot ? new Color(0xFFD34D) : new Color(0x14161A));
		g.setStroke(new java.awt.BasicStroke(idx == activeHotSlot ? 2f : 1f));
		g.drawRect(0, 0, w - 1, h - 1);
	}

	/** Short label for a hotbar slot, shown in its name band. */
	private String hotSlotName(HotSlot s)
	{
		if (s == null || s.tool == null)
		{
			return "";
		}
		try
		{
			switch (Tool.valueOf(s.tool))
			{
				case PLACE_OBJECT:
				{
					var d = service.getObject(toInt(s.objId, -1));
					String n = d != null ? d.getName() : null;
					return (n != null && !n.isEmpty() && !"null".equals(n)) ? n : "Obj " + s.objId;
				}
				case PLACE_NPC:
					return (s.npcName != null && !s.npcName.isEmpty()) ? s.npcName : "NPC " + s.npcId;
				case UNDERLAY:
					return "Under " + s.underlay;
				case OVERLAY:
					return "Over " + s.overlay;
				case HEIGHT:
					return "H " + (s.height == null || s.height.isEmpty() ? "auto" : s.height);
				case SETTINGS:
					return "Flag " + s.settings;
				case DELETE_OBJECT:
					return "Delete";
				case SELECT:
					return "Select";
				default:
					return s.tool;
			}
		}
		catch (RuntimeException ex)
		{
			return "";
		}
	}

	private void showHotSlotMenu(MouseEvent e, int idx)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem assign = new javax.swing.JMenuItem("Assign current tool + parameters");
		assign.addActionListener(a ->
		{
			hotSlots[idx] = captureHotSlot();
			rebuildHotIcon(idx);
			saveHotbar();
			hotComps[idx].repaint();
		});
		menu.add(assign);
		javax.swing.JMenuItem clear = new javax.swing.JMenuItem("Clear slot");
		clear.addActionListener(a ->
		{
			hotSlots[idx] = null;
			hotIcons[idx] = null;
			saveHotbar();
			hotComps[idx].repaint();
		});
		menu.add(clear);
		menu.show(hotComps[idx], e.getX(), e.getY());
	}

	private HotSlot captureHotSlot()
	{
		HotSlot s = new HotSlot();
		s.tool = tool.name();
		s.underlay = underlayField.getText();
		s.overlay = overlayField.getText();
		s.path = overlayPathField.getText();
		s.rot = overlayRotField.getText();
		s.height = heightField.getText();
		s.settings = settingsField.getText();
		s.objId = objectIdField.getText();
		s.objType = objectTypeField.getText();
		s.objRot = objectRotField.getText();
		s.npcId = placeNpcId >= 0 ? String.valueOf(placeNpcId) : null;
		s.npcName = placeNpcName;
		s.npcDir = String.valueOf(placeNpcDir);
		return s;
	}

	private void applyHotSlot(int idx)
	{
		HotSlot s = hotSlots[idx];
		if (s == null || s.tool == null)
		{
			return;
		}
		underlayField.setText(nz(s.underlay));
		overlayField.setText(nz(s.overlay));
		overlayPathField.setText(nz(s.path));
		overlayRotField.setText(nz(s.rot));
		heightField.setText(s.height == null ? "" : s.height);
		settingsField.setText(nz(s.settings));
		objectIdField.setText(nz(s.objId));
		objectTypeField.setText(nz(s.objType));
		objectRotField.setText(nz(s.objRot));
		if (s.npcId != null)
		{
			placeNpcId = toInt(s.npcId, -1);
			placeNpcName = nz(s.npcName);
			placeNpcDir = toInt(s.npcDir, 0);
		}
		try
		{
			setTool(Tool.valueOf(s.tool));
		}
		catch (IllegalArgumentException ignored)
		{
		}
		int old = activeHotSlot;
		activeHotSlot = idx;
		if (old >= 0)
		{
			hotComps[old].repaint();
		}
		hotComps[idx].repaint();
		status.setText(" Slot " + HOT_KEYS[idx] + " → " + s.tool);
	}

	private void installHotkeys()
	{
		javax.swing.JComponent root = getRootPane();
		for (int i = 0; i < HOT_KEYS.length; i++)
		{
			final int idx = i;
			String action = "hotslot" + i;
			int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(HOT_KEYS[i].charAt(0));
			root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(javax.swing.KeyStroke.getKeyStroke(keyCode, 0), action);
			root.getActionMap().put(action, new javax.swing.AbstractAction()
			{
				public void actionPerformed(java.awt.event.ActionEvent e)
				{
					if (hotkeysBlocked())
					{
						return;
					}
					// X or R rotates: the selected object/NPC if one is selected, else the
					// placement rotation when the Place tool is armed (falls back to hotbar slot).
					if ("X".equals(HOT_KEYS[idx]) || "R".equals(HOT_KEYS[idx]))
					{
						if (selectedLoc != null) { rotateLocation(selectedLoc); return; }
						if (selectedNpc != null) { rotateSelectedNpc(); return; }
						if (tool == Tool.PLACE_OBJECT) { rotatePlacement(); return; }
					}
					applyHotSlot(idx);
				}
			});
		}
	}

	/** Hotkeys must not fire while typing in a field or flying the 3D camera. */
	private boolean hotkeysBlocked()
	{
		java.awt.Component fo =
			java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (fo == canvas3D)
		{
			return true; // WASD/QE fly-camera owns the keys there
		}
		for (java.awt.Component c = fo; c != null; c = c.getParent())
		{
			if (c instanceof javax.swing.text.JTextComponent || c instanceof javax.swing.JSpinner)
			{
				return true;
			}
		}
		return false;
	}

	private void saveHotbar()
	{
		try (java.io.Writer w = new java.io.FileWriter(HOTBAR_FILE))
		{
			new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hotSlots, w);
		}
		catch (Exception ex)
		{
			System.err.println("Couldn't save hotbar: " + ex.getMessage());
		}
	}

	private void loadHotbar()
	{
		if (!HOTBAR_FILE.exists())
		{
			return;
		}
		try (java.io.Reader r = new java.io.FileReader(HOTBAR_FILE))
		{
			HotSlot[] loaded = new com.google.gson.Gson().fromJson(r, HotSlot[].class);
			if (loaded != null)
			{
				for (int i = 0; i < Math.min(loaded.length, hotSlots.length); i++)
				{
					hotSlots[i] = loaded[i];
					rebuildHotIcon(i);
				}
			}
		}
		catch (Exception ex)
		{
			System.err.println("Couldn't load hotbar: " + ex.getMessage());
		}
	}

	private static int toInt(String s, int def)
	{
		try
		{
			return Integer.parseInt(s.trim());
		}
		catch (Exception ex)
		{
			return def;
		}
	}

	private static String nz(String s)
	{
		return s == null ? "0" : s;
	}

	/** Draw an overlay shape glyph (path 0..11) at rotation, for pickers/hotbar. */
	private javax.swing.Icon shapeIcon(int path, int rotation)
	{
		int sz = 20;
		java.awt.image.BufferedImage img =
			new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics g = img.getGraphics();
		g.setColor(new Color(0x3A3F44));
		g.fillRect(0, 0, sz, sz);
		g.setColor(new Color(0x6FA8DC));
		byte[] mask = path == 0 ? null : MapRenderer.maskFor(path, rotation);
		if (mask == null)
		{
			g.fillRect(0, 0, sz, sz);
		}
		else
		{
			int s = MapRenderer.SHAPE_SCALE;
			double sub = sz / (double) s;
			int idx = 0;
			for (int iy = 0; iy < s; iy++)
			{
				for (int ix = 0; ix < s; ix++)
				{
					if (mask[idx++] != 0)
					{
						g.fillRect((int) (ix * sub), (int) (iy * sub),
							(int) Math.ceil(sub), (int) Math.ceil(sub));
					}
				}
			}
		}
		g.dispose();
		return new javax.swing.ImageIcon(img);
	}

	private JCheckBox layerCheck(String label, java.util.function.Supplier<Boolean> get,
		java.util.function.Consumer<Boolean> set)
	{
		JCheckBox cb = new JCheckBox(label, get.get());
		cb.setAlignmentX(LEFT_ALIGNMENT);
		cb.addActionListener(e -> { set.accept(cb.isSelected()); sceneDirty = true; rerender(); });
		return cb;
	}

	private JComponent swatch(int rgb, String tip, Runnable onClick)
	{
		final Color color = new Color(rgb);
		JComponent c = new JComponent()
		{
			@Override protected void paintComponent(Graphics g)
			{
				g.setColor(color);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(new Color(0, 0, 0, 90));
				g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
			}
		};
		c.setPreferredSize(new Dimension(46, 34));
		c.setToolTipText(tip);
		c.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent e) { onClick.run(); }
		});
		return c;
	}

	private JComponent wrapScroll(JComponent inner, String header)
	{
		JPanel p = new JPanel(new BorderLayout());
		JLabel h = new JLabel(" " + header);
		h.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
		p.add(h, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(inner);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		p.add(sp, BorderLayout.CENTER);
		return p;
	}

	private void setTool(Tool t)
	{
		tool = t;
		javax.swing.AbstractButton b = toolRadios.get(t);
		if (b != null)
		{
			b.setSelected(true);
		}
		javax.swing.AbstractButton rb = ribbonTools.get(t);
		if (rb != null)
		{
			rb.setSelected(true);
		}
		if (t == Tool.HEIGHT)
		{
			status.setText(" Height: type a value → every click sets it fixed · leave blank → click raises / right-click lowers by step");
		}
		else if (t == Tool.UNDERLAY || t == Tool.OVERLAY)
		{
			status.setText(" " + (t == Tool.UNDERLAY ? "Underlay" : "Overlay")
				+ ": click a tile to paint · Alt-click a tile to pick its id (eyedropper)");
		}
	}

	private JComponent title(String text)
	{
		JLabel l = new JLabel(text.toUpperCase());
		l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD, 11f));
		l.setForeground(new Color(0x8AB4F8));
		l.setAlignmentX(LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x3A3F47)),
			BorderFactory.createEmptyBorder(10, 0, 4, 0)));
		l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return l;
	}


	/** A small muted label used as a group caption in toolbars. */
	private JLabel chip(String text)
	{
		JLabel l = new JLabel(text + " ");
		l.setForeground(new Color(0x9AA0A6));
		return l;
	}

	/** The vertical tool column on the left, like before. */
	private JComponent buildToolColumn()
	{
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
		ButtonGroup group = new ButtonGroup();
		col.add(toolToggle(group, "Select", Tool.SELECT, true));
		col.add(toolToggle(group, "Underlay", Tool.UNDERLAY, false));
		col.add(toolToggle(group, "Overlay", Tool.OVERLAY, false));
		col.add(toolToggle(group, "Height", Tool.HEIGHT, false));
		col.add(toolToggle(group, "Flags", Tool.SETTINGS, false));
		col.add(toolToggle(group, "Place", Tool.PLACE_OBJECT, false));
		col.add(toolToggle(group, "NPC", Tool.PLACE_NPC, false));
		col.add(toolToggle(group, "Delete", Tool.DELETE_OBJECT, false));
		// User-pinned toggle buttons (chosen in File > Settings) fill the free slots below.
		pinnedArea = new JPanel();
		pinnedArea.setLayout(new BoxLayout(pinnedArea, BoxLayout.Y_AXIS));
		pinnedArea.setOpaque(false);
		pinnedArea.setAlignmentX(LEFT_ALIGNMENT);
		col.add(pinnedArea);
		rebuildPinnedArea();
		// Reserved filler so the tool strip runs unbroken to the bottom — it stretches
		// to fill whatever space is left and draws faint empty "slots" like the buttons.
		JComponent reserved = new JComponent()
		{
			@Override protected void paintComponent(Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				for (int y = 0; y + 32 <= getHeight(); y += 40)
				{
					g2.setColor(new Color(255, 255, 255, 8));
					g2.fillRoundRect(0, y + 2, getWidth(), 34, 8, 8);
					g2.setColor(new Color(0, 0, 0, 40));
					g2.drawRoundRect(0, y + 2, getWidth() - 1, 34, 8, 8);
				}
			}
		};
		reserved.setOpaque(false);
		reserved.setPreferredSize(new Dimension(120, 40));
		reserved.setMaximumSize(new Dimension(120, Integer.MAX_VALUE));
		reserved.setAlignmentX(LEFT_ALIGNMENT);
		col.add(reserved);
		return col;
	}

	/** Every ribbon toggle/button that can be pinned to the sidebar (View, Terrain, Tools, Objects). */
	private void buildPinnable()
	{
		// --- View: show toggles ---
		pinnable.add(new PinItem("Grid", () -> renderOptions.showGrid,
			v -> { renderOptions.showGrid = v; rerender(); }));
		pinnable.add(new PinItem("Scenery", () -> renderOptions.showScenery,
			v -> { renderOptions.showScenery = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Roofs", () -> renderOptions.showRoofs,
			v -> { renderOptions.showRoofs = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Wall decor", () -> renderOptions.showWallDecor,
			v -> { renderOptions.showWallDecor = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Walls", () -> renderOptions.showWalls,
			v -> { renderOptions.showWalls = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Doors", () -> renderOptions.showDoors,
			v -> { renderOptions.showDoors = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Ground decor", () -> renderOptions.showGroundDecor,
			v -> { renderOptions.showGroundDecor = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Icons", () -> renderOptions.showIcons,
			v -> { renderOptions.showIcons = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Flags", () -> renderOptions.showFlags,
			v -> { renderOptions.showFlags = v; rerender(); }));
		pinnable.add(new PinItem("Height tint", () -> renderOptions.showHeightTint,
			v -> { renderOptions.showHeightTint = v; rerender(); }));
		pinnable.add(new PinItem("Elements bar", () -> hotbar.isVisible(),
			v -> { hotbar.setVisible(v); relayout(); }));
		pinnable.add(new PinItem("Textures", () -> sceneBuilder.isTextures(),
			v -> { sceneBuilder.setTextures(v); sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Tile heights", () -> showHeights,
			v -> { showHeights = v; canvas3D.repaint(); if (splitMode) { canvas2D.repaint(); } }));
		pinnable.add(new PinItem("Buildable", () -> showBuildCheck,
			v -> { showBuildCheck = v; canvas3D.repaint(); if (splitMode) { canvas2D.repaint(); } }));
		pinnable.add(new PinItem("Wall conflicts", () -> showConflicts,
			v -> { showConflicts = v; canvas3D.repaint(); if (splitMode) { canvas2D.repaint(); } }));
		pinnable.add(new PinItem("Neighbours", () -> showNeighbors,
			v -> { showNeighbors = v; sceneBuilder.invalidateNeighbors(); sceneDirty = true; neighborSig2D = null; update2DCanvasSize(); if (show2D() || splitMode) { render2D(); } if (show3D()) { render3DFull(); } }));
		pinnable.add(new PinItem("All planes", () -> viewAllPlanes,
			v -> { viewAllPlanes = v; renderOptions.allPlanes = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Ghost below", () -> renderOptions.ghostLowerPlanes,
			v -> { renderOptions.ghostLowerPlanes = v; rerender(); }));
		pinnable.add(new PinItem("Compass", () -> showCompass,
			v -> { showCompass = v; canvas3D.repaint(); }));
		pinnable.add(new PinItem("3D view", () -> threeD, v -> toggle3D(v)));
		pinnable.add(new PinItem("Split view", () -> splitMode, v -> toggleSplit(v)));
		// --- Objects: spawns ---
		pinnable.add(new PinItem("Show spawns", () -> showSpawns,
			v -> { showSpawns = v; sceneDirty = true; rerender(); }));
		pinnable.add(new PinItem("Spawn names", () -> showSpawnNames,
			v -> { showSpawnNames = v; canvas2D.repaint(); }));

		// --- Action buttons ---
		pinnable.add(new PinItem("Save", this::save));
		pinnable.add(new PinItem("Save to server", this::saveToServer));
		pinnable.add(new PinItem("Open cache", this::openCache));
		pinnable.add(new PinItem("Reload region", this::reload));
		pinnable.add(new PinItem("Export TOML", this::exportRegionToml));
		pinnable.add(new PinItem("Undo", this::undo));
		pinnable.add(new PinItem("Redo", this::redo));
		pinnable.add(new PinItem("Go To", this::goToDialog));
		pinnable.add(new PinItem("Add Region", this::addRegionDialog));
		pinnable.add(new PinItem("Flatten plane", () -> flattenPlane(false)));
		pinnable.add(new PinItem("Flatten area", () -> flattenPlane(true)));
		pinnable.add(new PinItem("Smooth area", () -> smoothOverlay(true)));
		pinnable.add(new PinItem("Smooth plane", () -> smoothOverlay(false)));
		pinnable.add(new PinItem("Make bridge", this::makeBridge));
		pinnable.add(new PinItem("Clear bridge", this::clearBridge));
		pinnable.add(new PinItem("Zoom in (2D)", () -> setZoom(tileSize + 2)));
		pinnable.add(new PinItem("Zoom out (2D)", () -> setZoom(tileSize - 2)));
	}

	private PinItem findPin(String name)
	{
		for (PinItem p : pinnable)
		{
			if (p.name.equals(name)) { return p; }
		}
		return null;
	}

	/** (Re)builds the pinned toggle buttons in the sidebar's free slots from pinnedNames. */
	private void rebuildPinnedArea()
	{
		if (pinnedArea == null)
		{
			return;
		}
		pinnedArea.removeAll();
		for (String name : pinnedNames)
		{
			PinItem item = findPin(name);
			if (item == null)
			{
				continue;
			}
			javax.swing.AbstractButton b;
			if (item.isToggle())
			{
				JToggleButton tb = new JToggleButton(name, item.get.getAsBoolean());
				tb.addActionListener(e -> item.set.accept(tb.isSelected()));
				b = tb;
			}
			else
			{
				JButton ab = new JButton(name);
				ab.addActionListener(e -> item.action.run());
				b = ab;
			}
			b.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
			b.setFocusable(false);
			b.setMaximumSize(new Dimension(120, 32));
			b.setPreferredSize(new Dimension(120, 32));
			b.setAlignmentX(LEFT_ALIGNMENT);
			b.setToolTipText(name + " — remove in File > Settings");
			pinnedArea.add(b);
		}
		pinnedArea.revalidate();
		pinnedArea.repaint();
	}

	private void loadPinned()
	{
		if (!SIDEBAR_FILE.exists())
		{
			return;
		}
		try (java.io.Reader r = new java.io.FileReader(SIDEBAR_FILE))
		{
			String[] arr = new com.google.gson.Gson().fromJson(r, String[].class);
			if (arr != null)
			{
				pinnedNames.clear();
				for (String s : arr)
				{
					pinnedNames.add(s);
				}
			}
		}
		catch (Exception ignored)
		{
		}
	}

	private void savePinned()
	{
		try (java.io.Writer w = new java.io.FileWriter(SIDEBAR_FILE))
		{
			new com.google.gson.Gson().toJson(pinnedNames.toArray(new String[0]), w);
		}
		catch (Exception ignored)
		{
		}
	}

	/** File > Settings: choose which ribbon toggles appear in the sidebar's free slots. */
	private void showSettingsDialog()
	{
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		java.util.Map<String, JCheckBox> boxes = new java.util.LinkedHashMap<>();
		for (PinItem p : pinnable)
		{
			JCheckBox cb = new JCheckBox(p.name + (p.isToggle() ? "" : "  (button)"), pinnedNames.contains(p.name));
			boxes.put(p.name, cb);
			list.add(cb);
		}
		JScrollPane sp = new JScrollPane(list);
		sp.setPreferredSize(new Dimension(280, 420));
		JPanel panel = new JPanel(new BorderLayout(0, 6));
		panel.add(new JLabel("Tick items to add to the left sidebar (below the 8 fixed tools):"), BorderLayout.NORTH);
		panel.add(sp, BorderLayout.CENTER);
		int res = JOptionPane.showConfirmDialog(this, panel, "Settings — Sidebar buttons",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (res != JOptionPane.OK_OPTION)
		{
			return;
		}
		pinnedNames.clear();
		for (PinItem p : pinnable)
		{
			if (boxes.get(p.name).isSelected())
			{
				pinnedNames.add(p.name);
			}
		}
		savePinned();
		rebuildPinnedArea();
	}

	private javax.swing.AbstractButton toolToggle(ButtonGroup group, String text, Tool t, boolean selected)
	{
		JToggleButton b = new JToggleButton(text, makeToolIcon(t), selected);
		b.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
		b.setFocusable(false);
		b.setMaximumSize(new Dimension(120, 36));
		b.setPreferredSize(new Dimension(120, 36));
		b.setToolTipText(text);
		b.addActionListener(e -> setTool(t));
		group.add(b);
		toolRadios.put(t, b);
		return b;
	}

	private javax.swing.Icon makeToolIcon(Tool t)
	{
		int s = 18;
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new java.awt.BasicStroke(2f));
		switch (t)
		{
			case SELECT:
				g.setColor(new Color(0xEDEDED));
				g.fillPolygon(new int[]{3, 3, 8, 10, 13}, new int[]{2, 15, 11, 16, 10}, 5);
				break;
			case UNDERLAY:
				g.setColor(new Color(0x5CA83C));
				g.fillRoundRect(2, 2, s - 4, s - 4, 5, 5);
				break;
			case OVERLAY:
				g.setColor(new Color(0x4A78C8));
				g.fillRoundRect(2, 2, s - 4, s - 4, 5, 5);
				break;
			case HEIGHT:
				g.setColor(new Color(0xD8B24A));
				g.fillPolygon(new int[]{9, 4, 14}, new int[]{2, 8, 8}, 3);
				g.fillPolygon(new int[]{9, 4, 14}, new int[]{16, 10, 10}, 3);
				break;
			case SETTINGS:
				g.setColor(new Color(0xC8543C));
				g.fillRect(4, 2, 2, 14);
				g.fillRect(6, 3, 8, 6);
				break;
			case PLACE_OBJECT:
				g.setColor(new Color(0x5CC85C));
				g.fillRect(8, 3, 2, 12);
				g.fillRect(3, 8, 12, 2);
				break;
			case PLACE_NPC:
				g.setColor(new Color(0xE05555));
				g.fillOval(6, 2, 6, 6);   // head
				g.fillRect(5, 9, 8, 7);   // body
				break;
			case DELETE_OBJECT:
				g.setColor(new Color(0xE05050));
				g.drawLine(4, 4, 14, 14);
				g.drawLine(14, 4, 4, 14);
				break;
			default:
		}
		g.dispose();
		return new javax.swing.ImageIcon(img);
	}

	private JComponent labeled(String label, JComponent field)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		JLabel l = new JLabel(label);
		l.setForeground(new Color(0xC7CBD1));
		l.setPreferredSize(new Dimension(118, 24));
		p.add(l, BorderLayout.WEST);
		p.add(field, BorderLayout.CENTER);
		return p;
	}

	private JComponent row(JComponent a, JComponent b)
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.setOpaque(false);
		p.add(a);
		p.add(b);
		return p;
	}

	// ---- region loading / rendering -----------------------------------

	private void loadRegion(int regionId)
	{
		loadRegion(regionId, true);
	}

	/**
	 * Open one region out of an RSPSi {@code .pack} project file. A pack holds several regions in a
	 * grid but records no world coordinates for them, so the region to load as has to be asked for.
	 */
	private void openPackFile()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Open RSPSi .pack file");
		fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"RSPSi map pack (*.pack)", "pack"));
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		try
		{
			MapEditorService.PackEntry[] entries = MapEditorService.readPack(fc.getSelectedFile());
			if (entries.length == 0)
			{
				throw new java.io.IOException("Pack contains no regions.");
			}
			MapEditorService.PackEntry chosen = entries[0];
			if (entries.length > 1)
			{
				Object sel = JOptionPane.showInputDialog(this,
					"This pack holds " + entries.length + " regions. Which one?",
					"Open .pack", JOptionPane.QUESTION_MESSAGE, null, entries, entries[0]);
				if (sel == null)
				{
					return;
				}
				chosen = (MapEditorService.PackEntry) sel;
			}
			int defX = region != null ? region.getRegionX() : 50;
			int defY = region != null ? region.getRegionY() : 50;
			String in = JOptionPane.showInputDialog(this,
				"The pack stores no world coordinates.\nLoad this region as? (x,y)",
				defX + "," + defY);
			if (in == null)
			{
				return;
			}
			String[] parts = in.trim().split("\\s*,\\s*");
			if (parts.length != 2)
			{
				throw new java.io.IOException("Enter the region as x,y — e.g. 50,50");
			}
			RegionModel rm = service.regionFromPack(chosen,
				Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
			adoptRegion(rm, " [from .pack]");
			status.setText(" Opened pack region " + chosen + " as " + rm.getRegionId());
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Open .pack failed:\n" + ex.getMessage(),
				"Open .pack", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Make {@code rm} the region being edited: clear all per-region selection/undo state, recentre
	 * and re-render, and retitle. Shared by cache loads and loose-file opens so the two can't drift.
	 */
	private void adoptRegion(RegionModel rm, String titleNote)
	{
		region = rm;
		selX = selY = -1;
		selCorners3D = null;
		selectedLoc = null;
		selectedNpc = null;
		drag2DLoc = null;
		drag2DNpc = null;
		movingLoc = null;
		sceneBuilder.setHighlight(null);
		pulseTimer.stop();
		pulseOn = false;
		undoStack.clear();
		redoStack.clear();
		sceneDirty = true;
		centerCamera();
		fit2D();
		rerender();
		updateInspector();
		setTitle("OSRS Map Editor — region " + rm.getRegionId()
			+ " (" + rm.getRegionX() + "," + rm.getRegionY() + ")"
			+ (rm.hasLocationArchive ? "" : "  [no XTEA key: objects read-only]")
			+ titleNote);
	}

	/**
	 * Open a region from loose m/l files rather than the cache. The loaded region keeps the id its
	 * filename implies, so a later Save targets that region in the open cache — imported terrain can
	 * therefore overwrite a region you didn't dump it from. The title bar marks it [from files].
	 */
	private void openRegionFiles()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Open region — select m{x}_{y}.dat, or BOTH files of an id-named pair");
		fc.setMultiSelectionEnabled(true);
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File[] picked = fc.getSelectedFiles();
		if (picked.length == 0)
		{
			return;
		}
		try
		{
			RegionModel rm;
			// One m{x}_{y} file: the region comes from the name, and the sibling l file is found for us.
			if (picked.length == 1 && picked[0].getName().matches("^m\\d+_\\d+(\\.dat)?$"))
			{
				rm = service.loadRegionFromFiles(picked[0]);
			}
			else
			{
				// Foreign naming (e.g. RSPSi's archive ids, which don't match this cache's). The
				// region is in neither the filename nor the data, so ask; default to the open region.
				int defX = region != null ? region.getRegionX() : 50;
				int defY = region != null ? region.getRegionY() : 50;
				String in = JOptionPane.showInputDialog(this,
					"These files don't carry a region id.\nWhich region are they? (x,y)",
					defX + "," + defY);
				if (in == null)
				{
					return;
				}
				String[] parts = in.trim().split("\\s*,\\s*");
				if (parts.length != 2)
				{
					throw new java.io.IOException("Enter the region as x,y — e.g. 50,50");
				}
				rm = service.loadRegionFromFiles(picked,
					Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
			}
			adoptRegion(rm, " [from files]");
			status.setText(" Opened " + picked.length + " file(s) as region " + rm.getRegionId()
				+ (rm.hasLocationArchive ? "" : " — terrain only, no locations file"));
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Open failed:\n" + ex.getMessage(),
				"Open m/l files", JOptionPane.ERROR_MESSAGE);
		}
	}

	private boolean loadRegion(int regionId, boolean showError)
	{
		try
		{
			adoptRegion(service.loadRegion(regionId), "");
			return true;
		}
		catch (Throwable ex)
		{
			lastLoadError = ex;
			if (showError)
			{
				ex.printStackTrace();
				JOptionPane.showMessageDialog(this, "Failed to load region " + regionId + ":\n" + ex,
					"Error", JOptionPane.ERROR_MESSAGE);
			}
			return false;
		}
	}

	/** Load the requested region, or fall back to the first region that loads. */
	private void loadInitialRegion(int regionId)
	{
		if (loadRegion(regionId, false))
		{
			return;
		}
		// Try a limited number of other regions (don't spam thousands of failures).
		int tries = 0;
		for (int r : service.listRegions())
		{
			if (r == regionId)
			{
				continue;
			}
			if (loadRegion(r, false))
			{
				status.setText(" Region " + regionId + " wouldn't load; opened region " + r + " instead.");
				return;
			}
			if (++tries >= 20)
			{
				break;
			}
		}
		if (lastLoadError != null)
		{
			lastLoadError.printStackTrace(); // print ONCE for diagnosis
		}
		boolean formatIssue = lastLoadError instanceof java.nio.BufferUnderflowException
			|| lastLoadError instanceof IndexOutOfBoundsException;
		String reason = formatIssue
			? "its terrain data decodes to a different format than this build supports"
			: String.valueOf(lastLoadError);
		JOptionPane.showMessageDialog(this,
			"Couldn't decode maps from this cache (" + reason + ").\n\n"
				+ "This build's map loader is tuned for your server's cache revision; other\n"
				+ "OSRS revisions can use a different terrain encoding. Your server's cache works.",
			"Unsupported cache", JOptionPane.ERROR_MESSAGE);
	}

	// which views are currently on screen
	private boolean show3D() { return splitMode || threeD; }
	private boolean show2D() { return splitMode || !threeD; }

	/** Rebuild the CENTER of the frame for the current single/split mode. */
	private void updateCenter()
	{
		if (centerComp != null)
		{
			centerWrap.remove(centerComp);
		}
		java.awt.Component c;
		if (splitMode)
		{
			if (splitHorizontal)
			{
				// official-editor layout: 2D left, 3D right
				splitPane.setOrientation(javax.swing.JSplitPane.HORIZONTAL_SPLIT);
				splitPane.setLeftComponent(canvasScroll);
				splitPane.setRightComponent(canvas3D);
			}
			else
			{
				splitPane.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
				splitPane.setTopComponent(canvas3D);
				splitPane.setBottomComponent(canvasScroll);
			}
			c = splitPane;
		}
		else if (threeD)
		{
			c = canvas3D;
		}
		else
		{
			c = canvasScroll;
		}
		centerWrap.add(c, BorderLayout.CENTER);
		centerComp = c;
		centerWrap.revalidate();
		centerWrap.repaint();
	}

	private void setZoom(int newSize)
	{
		tileSize = Math.max(3, Math.min(100, newSize));
		manual2DZoom = true;
		update2DCanvasSize();
		render2D();
	}

	private void startPan2D(MouseEvent e)
	{
		panning2D = true;
		panStartScreen = e.getLocationOnScreen();
		panStartView = canvasScroll.getViewport().getViewPosition();
		canvas2D.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
	}

	private void doPan2D(MouseEvent e)
	{
		if (!panning2D || panStartScreen == null)
		{
			return;
		}
		java.awt.Point now = e.getLocationOnScreen();
		int dx = panStartScreen.x - now.x;
		int dy = panStartScreen.y - now.y;
		javax.swing.JViewport vp = canvasScroll.getViewport();
		Dimension pref = canvas2D.getPreferredSize();
		int nx = Math.max(0, Math.min(panStartView.x + dx, Math.max(0, pref.width - vp.getWidth())));
		int ny = Math.max(0, Math.min(panStartView.y + dy, Math.max(0, pref.height - vp.getHeight())));
		vp.setViewPosition(new java.awt.Point(nx, ny));
	}

	/** Fit the 2D map to fill its viewport, centred. */
	private void fit2D()
	{
		Dimension vp = canvasScroll.getViewport().getExtentSize();
		int vw = Math.max(64, vp.width), vh = Math.max(64, vp.height);
		tileSize = Math.max(3, Math.min(40, Math.min(vw, vh) / 64));
		manual2DZoom = false;
		update2DCanvasSize();
		render2D();
	}

	/** Size the 2D canvas to at least the viewport so the map fills + centres. */
	private void update2DCanvasSize()
	{
		Dimension vp = canvasScroll.getViewport().getExtentSize();
		int w = Math.max(image2DSize(), vp.width);
		int h = Math.max(image2DSize(), vp.height);
		canvas2D.setPreferredSize(new Dimension(w, h));
		canvas2D.revalidate();
	}

	private void rerender()
	{
		if (region != null)
		{
			if (show2D())
			{
				render2D();
			}
			if (show3D())
			{
				render3DFull();
			}
		}
		updateStatus();
	}

	private void render2D()
	{
		if (region != null)
		{
			if (showNeighbors)
			{
				ensureNeighborModels2D();
				image2D = renderer.renderWithNeighbors(region, neighborModels2D, plane, tileSize,
					net.runelite.cache.region.Region.X, renderOptions);
			}
			else
			{
				image2D = renderer.render(region, plane, tileSize, renderOptions);
			}
			canvas2D.repaint();
			if (popout2DFrame != null && popout2DFrame.isVisible()) popout2DFrame.repaint();
			if (minimap != null)
			{
				minimapImage = renderer.render(region, plane, 4, renderOptions);
				minimap.repaint();
			}
		}
	}

	private int neighbor2DStrip() { return showNeighbors ? net.runelite.cache.region.Region.X * tileSize : 0; }
	private int image2DSize() { return 64 * tileSize + 2 * neighbor2DStrip(); }

	private void ensureNeighborModels2D()
	{
		int rx = region.getRegionX(), ry = region.getRegionY();
		String sig = region.getRegionId() + "/" + plane;
		if (sig.equals(neighborSig2D)) { return; }
		neighborSig2D = sig;
		int[] nb = {
			(rx << 8) | (ry + 1), (rx << 8) | (ry - 1),
			((rx + 1) << 8) | ry, ((rx - 1) << 8) | ry,
			((rx + 1) << 8) | (ry + 1), ((rx - 1) << 8) | (ry + 1),
			((rx + 1) << 8) | (ry - 1), ((rx - 1) << 8) | (ry - 1),
		};
		for (int i = 0; i < 8; i++)
		{
			neighborModels2D[i] = null;
			try { neighborModels2D[i] = service.loadRegion(nb[i]); }
			catch (Throwable ignored) {}
		}
	}

	private void ensureScene()
	{
		if (scene3D == null || sceneDirty)
		{
			if (animating)
			{
				// Static base (terrain + non-animated objects) — rebuilt only when the map/region
				// actually changes. The animated objects are appended on top (here for the current
				// frame; refreshed each tick in tickAnimation). One scene = one source of truth, so
				// placing objects / changing region while animating shows up correctly.
				scene3D = sceneBuilder.buildStatic(region, plane, renderOptions.showObjects,
					showSpawns ? service.getSpawnsInRegion(region.getRegionId()) : null);
				if (showNeighbors)
				{
					sceneBuilder.appendNeighborStrips(scene3D, region, plane, renderOptions.showObjects,
						net.runelite.cache.region.Region.X);
				}
				animBaseSize = scene3D.size();
				sceneBuilder.appendAnimatedObjects(scene3D, region, plane, animTick,
			showSpawns ? service.getSpawnsInRegion(region.getRegionId()) : null);
			}
			else
			{
				setStatusBusy("Building 3D scene…");
				scene3D = sceneBuilder.build(region, plane, renderOptions.showObjects,
					showSpawns ? service.getSpawnsInRegion(region.getRegionId()) : null, -1);
				if (showNeighbors)
				{
					sceneBuilder.appendNeighborStrips(scene3D, region, plane, renderOptions.showObjects,
						net.runelite.cache.region.Region.X);
				}
			}
			rebuildPlaneGrid();
			sceneDirty = false;
		}
	}

	private void toggleAnimate(boolean on)
	{
		animating = on;
		if (on && show3D())
		{
			animStartMs = System.currentTimeMillis();
			sceneDirty = true; // force ensureScene to (re)build as a static base + animated overlay
			animTimer.start();
		}
		else
		{
			animTimer.stop();
			sceneDirty = true;
			if (show3D())
			{
				render3DFull();
			}
		}
	}

	private void tickAnimation()
	{
		if (!show3D() || !animating)
		{
			animTimer.stop();
			return;
		}
		// Rebuilds the static base if an edit / region change set sceneDirty (fixes objects vanishing
		// and stale region while animating); otherwise reuses it.
		ensureScene();
		// Time-based frame so playback speed is constant even if a render occasionally lags.
		animTick = (int) ((System.currentTimeMillis() - animStartMs) / ANIM_FRAME_MS);
		scene3D.truncateTo(animBaseSize);
		sceneBuilder.appendAnimatedObjects(scene3D, region, plane, animTick,
			showSpawns ? service.getSpawnsInRegion(region.getRegionId()) : null);
		// Full-res (crisp) while still; fast renderer only while actively moving/orbiting (unless
		// "Sharp while moving" forces full-res throughout).
		Renderer3D r = (sharpWhileMoving || (keysDown.isEmpty() && !orbiting)) ? renderer3D : renderer3DFast;
		image3D = r.render(scene3D, camera, texSource);
		canvas3D.repaint();
		if (popout3DFrame != null && popout3DFrame.isVisible()) popout3DFrame.repaint();
	}

	/** Render at reduced resolution for responsiveness while interacting. */
	private void render3DFast()
	{
		ensureScene();
		// "Sharp while moving" uses the full-res renderer even during motion (needs a stronger PC).
		Renderer3D r = sharpWhileMoving ? renderer3D : renderer3DFast;
		image3D = r.render(scene3D, camera, texSource);
		canvas3D.repaint();
		if (popout3DFrame != null && popout3DFrame.isVisible()) popout3DFrame.repaint();
	}

	private void render3DFull()
	{
		ensureScene();
		image3D = renderer3D.render(scene3D, camera, texSource);
		canvas3D.repaint();
		if (popout3DFrame != null && popout3DFrame.isVisible()) popout3DFrame.repaint();
	}

	private JFrame buildPopoutFrame(String title, boolean is3D)
	{
		JFrame frame = new JFrame(title);
		frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		JPanel panel = new JPanel()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				BufferedImage img = is3D ? image3D : image2D;
				if (img != null)
				{
					g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
				}
				else
				{
					g.setColor(java.awt.Color.DARK_GRAY);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
			}
		};
		panel.setPreferredSize(new java.awt.Dimension(960, 720));
		panel.setBackground(java.awt.Color.BLACK);
		frame.add(panel);
		frame.pack();
		frame.setLocationRelativeTo(null);
		return frame;
	}

	private void togglePopout2D()
	{
		if (popout2DFrame == null)
		{
			popout2DFrame = buildPopoutFrame("Map Editor — 2D View", false);
		}
		popout2DFrame.setVisible(!popout2DFrame.isVisible());
		if (popout2DFrame.isVisible()) popout2DFrame.repaint();
	}

	private void togglePopout3D()
	{
		if (popout3DFrame == null)
		{
			popout3DFrame = buildPopoutFrame("Map Editor — 3D View", true);
		}
		popout3DFrame.setVisible(!popout3DFrame.isVisible());
		if (popout3DFrame.isVisible()) popout3DFrame.repaint();
	}

	/** Map a click in the 3D canvas to a tile (x,y) via a colour-id pick pass. */
	private int[] pickTile(int canvasX, int canvasY)
	{
		if (region == null)
		{
			return null;
		}
		Renderer3D.Scene pick = sceneBuilder.buildPickScene(region, plane);
		java.awt.image.BufferedImage pimg = pickRenderer.render(pick, camera); // flat ids, no texture
		int cw = Math.max(1, canvas3D.getWidth());
		int ch = Math.max(1, canvas3D.getHeight());
		int bx = (int) ((long) canvasX * view3dW / cw);
		int by = (int) ((long) canvasY * view3dH / ch);
		if (bx < 0 || by < 0 || bx >= view3dW || by >= view3dH)
		{
			return null;
		}
		int id = (pimg.getRGB(bx, by) & 0xFFFFFF) - 1;
		if (id < 0 || id >= 64 * 64)
		{
			return null;
		}
		return new int[]{id & 63, id >> 6};
	}

	/** Map a click to the object (Location) under it via a colour-id pass over models. */
	private Location pickObjectAt(int canvasX, int canvasY)
	{
		objPickList.clear();
		Renderer3D.Scene pick = sceneBuilder.buildObjectPickScene(region, plane, objPickList);
		java.awt.image.BufferedImage pimg = pickRenderer.render(pick, camera);
		int cw = Math.max(1, canvas3D.getWidth());
		int ch = Math.max(1, canvas3D.getHeight());
		int bx = (int) ((long) canvasX * view3dW / cw);
		int by = (int) ((long) canvasY * view3dH / ch);
		if (bx < 0 || by < 0 || bx >= view3dW || by >= view3dH)
		{
			return null;
		}
		int id = (pimg.getRGB(bx, by) & 0xFFFFFF) - 1;
		if (id < 0 || id >= objPickList.size())
		{
			return null;
		}
		return objPickList.get(id);
	}

	private void leftClick3D(MouseEvent e)
	{
		paintLower = e.isShiftDown();
		paintAbsolute = e.isControlDown();
		if (movingLoc != null)
		{
			int[] t = pickTile(e.getX(), e.getY());
			if (t != null)
			{
				pushUndo();
				moveLocationTo(movingLoc, t[0], t[1]);
			}
			movingLoc = null;
			return;
		}
		if (pasteTilesMode && tileStamp != null)
		{
			int[] t = pickTile(e.getX(), e.getY());
			if (t != null) { stampTilesAt(t[0], t[1]); }
			return;
		}
		if (tool == Tool.SELECT)
		{
			Location loc = pickObjectAt(e.getX(), e.getY());
			if (loc != null)
			{
				selectLoc(loc);
			}
			else
			{
				// No object under the cursor: select the ground tile, just like 2D —
				// sets the selection and shows the projected yellow tile highlight.
				int[] t = pickTile(e.getX(), e.getY());
				if (t != null)
				{
					clearSelection();
					clearNpcSelection();
					selX = t[0];
					selY = t[1];
					updateSelCorners();
					updateInspector();
					canvas3D.repaint();
					canvas2D.repaint();
				}
				else
				{
					clearSelection();
				}
			}
		}
		else
		{
			int[] t = pickTile(e.getX(), e.getY());
			if (t != null)
			{
				if (e.isAltDown() && isTerrainTool())
				{
					pickTerrain(t[0], t[1]);
					return;
				}
				pushUndo();
				applyTool(t[0], t[1]);
			}
		}
	}

	private void rightClick3D(MouseEvent e)
	{
		Location loc = pickObjectAt(e.getX(), e.getY());
		if (loc == null)
		{
			int[] t = pickTile(e.getX(), e.getY());
			if (t != null) { showTilePasteMenu(e, t[0], t[1]); }
			return;
		}
		selectLoc(loc);
		showObjectMenu(e, loc);
	}

	private void showObjectMenu(MouseEvent e, Location loc)
	{
		net.runelite.cache.definitions.ObjectDefinition def = service.getObject(loc.getId());
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem info = new javax.swing.JMenuItem(loc.getId() + "  " + (def != null ? def.getName() : ""));
		info.setEnabled(false);
		menu.add(info);
		menu.addSeparator();
		javax.swing.JMenuItem rotate = new javax.swing.JMenuItem("Rotate 90°");
		rotate.addActionListener(a -> rotateLocation(loc));
		menu.add(rotate);
		javax.swing.JMenuItem move = new javax.swing.JMenuItem("Move (then left-click a tile)");
		move.addActionListener(a ->
		{
			movingLoc = loc;
			status.setText(" Move: left-click a tile to place object " + loc.getId());
		});
		menu.add(move);
		javax.swing.JMenuItem del = new javax.swing.JMenuItem("Delete");
		del.addActionListener(a -> deleteLocation(loc));
		menu.add(del);
		menu.addSeparator();
		javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy");
		copy.addActionListener(a -> copyLocation(loc));
		menu.add(copy);
		javax.swing.JMenuItem paste = new javax.swing.JMenuItem("Paste here");
		paste.setEnabled(clipboard != null && !clipboard.isEmpty());
		paste.addActionListener(a -> pasteAt(loc.getPosition().getX(), loc.getPosition().getY()));
		menu.add(paste);
		menu.addSeparator();
		menu.add(displayRotMenu(loc.getId()));
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	/**
	 * Submenu to set a DISPLAY-ONLY rotation offset for this object id — corrects how a
	 * mis-rendered custom object looks in the editor without touching any saved data (so the
	 * game is unaffected). Applies to every placement of that id.
	 */
	private javax.swing.JMenu displayRotMenu(int objId)
	{
		javax.swing.JMenu rotMenu = new javax.swing.JMenu("Editor display rotation (view only)");
		rotMenu.setToolTipText("Rotate how this object looks in the editor only — does NOT change map data / the game.");
		int cur = sceneBuilder.getDisplayRot(objId);
		String[] labels = {"0° (none)", "+90°", "+180°", "+270°"};
		javax.swing.ButtonGroup grp = new javax.swing.ButtonGroup();
		for (int st = 0; st < 4; st++)
		{
			final int steps = st;
			javax.swing.JRadioButtonMenuItem it = new javax.swing.JRadioButtonMenuItem(labels[st], cur == steps);
			grp.add(it);
			it.addActionListener(a ->
			{
				sceneBuilder.setDisplayRot(objId, steps);
				saveObjRot();
				sceneDirty = true;
				rerender();
				status.setText(" Editor display rotation for obj " + objId + " = +" + (steps * 90)
					+ "°  (view only — map data unchanged)");
			});
			rotMenu.add(it);
		}
		return rotMenu;
	}

	private static final File OBJROT_FILE =
		new File(System.getProperty("user.home"), ".osrs-map-editor-objrot.json");

	private void loadObjRot()
	{
		if (!OBJROT_FILE.exists())
		{
			return;
		}
		try (java.io.Reader r = new java.io.FileReader(OBJROT_FILE))
		{
			java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.Map<String, Integer>>() { }.getType();
			java.util.Map<String, Integer> m = new com.google.gson.Gson().fromJson(r, t);
			if (m != null)
			{
				for (java.util.Map.Entry<String, Integer> en : m.entrySet())
				{
					try { sceneBuilder.setDisplayRot(Integer.parseInt(en.getKey()), en.getValue()); }
					catch (Exception ignored) { }
				}
			}
		}
		catch (Exception ignored)
		{
		}
	}

	private void saveObjRot()
	{
		try (java.io.Writer w = new java.io.FileWriter(OBJROT_FILE))
		{
			java.util.Map<String, Integer> m = new java.util.HashMap<>();
			for (java.util.Map.Entry<Integer, Integer> en : sceneBuilder.displayRotMap().entrySet())
			{
				m.put(String.valueOf(en.getKey()), en.getValue());
			}
			new com.google.gson.Gson().toJson(m, w);
		}
		catch (Exception ignored)
		{
		}
	}

	// ---- 2D object / NPC interaction -----------------------------------

	private int[] tile2D(MouseEvent e)
	{
		int ox = Math.max(0, (canvas2D.getWidth() - image2DSize()) / 2) + neighbor2DStrip();
		int oy = Math.max(0, (canvas2D.getHeight() - image2DSize()) / 2) + neighbor2DStrip();
		int x = (e.getX() - ox) / tileSize;
		int y = 63 - (e.getY() - oy) / tileSize;
		return (x < 0 || x > 63 || y < 0 || y > 63) ? null : new int[]{x, y};
	}

	private Location topObjectAt(int x, int y)
	{
		java.util.List<Location> here = region.locationsAt(plane, x, y);
		// Topmost location that passes the click-select filter AND is on a visible layer, so a
		// right-click can't grab a hidden layer (e.g. Ground decor off → click through to the object).
		for (int i = here.size() - 1; i >= 0; i--)
		{
			Location l = here.get(i);
			if (locMatchesFilter(l.getType()) && locVisible(l))
			{
				return l;
			}
		}
		return null;
	}

	/** True if a location's type passes the current click-select filter. */
	private boolean locMatchesFilter(int type)
	{
		switch (selFilter)
		{
			case 1: return type != 22;                              // Objects: anything but ground decoration
			case 2: return type == 22;                              // Ground decoration only
			case 3: return (type >= 0 && type <= 3) || type == 9;   // Walls (straight + diagonal)
			default: return true;                                   // All
		}
	}

	/**
	 * True if a location's layer is currently visible (its View toggle is on). Hidden layers aren't
	 * clickable, so unchecking e.g. "Ground decor" lets you click straight through to the object
	 * beneath it. Categories match the renderer: 22 = ground decor, 0-9 = walls, else scenery.
	 */
	private boolean locVisible(Location l)
	{
		return MapRenderer.catVisible(renderOptions, l.getType(), service.getObject(l.getId()));
	}

	/**
	 * Pick the location a 2D click should select on a (possibly stacked) tile, honouring the
	 * "Click selects" filter. Clicking the same tile again cycles down through the stack, so an
	 * object hidden under a ground decoration is still reachable. Returns null if nothing matches.
	 */
	private Location pickLocAt(int x, int y)
	{
		java.util.List<Location> here = region.locationsAt(plane, x, y);
		java.util.List<Location> matches = new java.util.ArrayList<>();
		for (Location l : here)
		{
			// Skip layers hidden via the View toggles, so you click through to what's visible.
			if (locMatchesFilter(l.getType()) && locVisible(l))
			{
				matches.add(l);
			}
		}
		lastMatchCount = matches.size();
		if (matches.isEmpty())
		{
			return null;
		}
		if (x == cycleTileX && y == cycleTileY)
		{
			// Same tile clicked again: step down through the stack (wraps around).
			cycleIdx = (cycleIdx - 1 + matches.size()) % matches.size();
		}
		else
		{
			cycleTileX = x;
			cycleTileY = y;
			cycleIdx = matches.size() - 1; // start at the top-most match
		}
		return matches.get(cycleIdx);
	}

	/**
	 * Left-click in 2D with the Select tool. When the Spawns layer is visible an NPC
	 * on the tile takes priority (they're drawn on top and are what you're working
	 * with); Alt+click falls through to the object beneath. With Spawns hidden,
	 * objects take priority as before.
	 */
	private void select2D(MouseEvent e)
	{
		int[] t = tile2D(e);
		if (t == null)
		{
			return;
		}
		Location loc = pickLocAt(t[0], t[1]);
		SpawnLoader.Spawn npc = showSpawns
			? service.spawnAt(region.getBaseX() + t[0], region.getBaseY() + t[1], plane) : null;

		if (npc != null && !e.isAltDown())
		{
			selectNpc(npc);
			drag2DNpc = npc;
			return;
		}
		if (loc != null)
		{
			pushUndo();
			selectLoc(loc);
			drag2DLoc = loc;
			if (lastMatchCount > 1)
			{
				int pos = lastMatchCount - cycleIdx; // 1 = top of stack
				status.setText(" Selected object id " + loc.getId() + " (type " + loc.getType()
					+ ", rot " + loc.getOrientation() + ")  ·  "
					+ pos + " of " + lastMatchCount + " on this tile — click again to cycle down");
			}
			return;
		}
		if (npc != null)
		{
			selectNpc(npc);
			drag2DNpc = npc;
			return;
		}
		// Empty ground: begin a marquee. A plain click (no drag) falls back to a
		// single-tile selection when the marquee is finished.
		clearSelection();
		clearNpcSelection();
		selectedLocs.clear();
		marqueeSelecting = true;
		marqStartX = marqCurX = t[0];
		marqStartY = marqCurY = t[1];
		canvas2D.repaint();
	}

	private void updateMarquee(MouseEvent e)
	{
		int[] t = tileClamped(e);
		marqCurX = t[0];
		marqCurY = t[1];
		canvas2D.repaint();
	}

	/** Finish a marquee drag: select every object whose tile lies inside the box. */
	private void finishMarquee(MouseEvent e)
	{
		marqueeSelecting = false;
		int[] t = tileClamped(e);
		marqCurX = t[0];
		marqCurY = t[1];
		if (marqStartX == marqCurX && marqStartY == marqCurY)
		{
			// No drag — treat as a plain tile click.
			selX = marqStartX;
			selY = marqStartY;
			updateSelCorners();
			updateInspector();
			canvas2D.repaint();
			return;
		}
		int minX = Math.min(marqStartX, marqCurX), maxX = Math.max(marqStartX, marqCurX);
		int minY = Math.min(marqStartY, marqCurY), maxY = Math.max(marqStartY, marqCurY);
		selectedLocs.clear();
		for (Location l : region.getLocations().getLocations())
		{
			net.runelite.cache.region.Position p = l.getPosition();
			if (p.getZ() == plane && p.getX() >= minX && p.getX() <= maxX
				&& p.getY() >= minY && p.getY() <= maxY)
			{
				selectedLocs.add(l);
			}
		}
		selX = -1;
		status.setText(" Selected " + selectedLocs.size() + " objects  ·  Ctrl+C copy · Del delete");
		canvas2D.repaint();
	}

	/** Tile coords for a mouse event, clamped to the 0..63 region bounds. */
	private int[] tileClamped(MouseEvent e)
	{
		int ox = Math.max(0, (canvas2D.getWidth() - image2DSize()) / 2) + neighbor2DStrip();
		int oy = Math.max(0, (canvas2D.getHeight() - image2DSize()) / 2) + neighbor2DStrip();
		int x = Math.max(0, Math.min(63, (e.getX() - ox) / tileSize));
		int y = Math.max(0, Math.min(63, 63 - (e.getY() - oy) / tileSize));
		return new int[]{x, y};
	}

	// ---- copy / paste / delete of the object multi-selection ----------

	private void copySelection()
	{
		java.util.Collection<Location> src = !selectedLocs.isEmpty()
			? selectedLocs : (selectedLoc != null ? java.util.List.of(selectedLoc) : java.util.List.of());
		if (src.isEmpty())
		{
			return;
		}
		// Anchor at the top-left of the selection so paste keeps the relative layout.
		int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE;
		for (Location l : src)
		{
			ax = Math.min(ax, l.getPosition().getX());
			ay = Math.min(ay, l.getPosition().getY());
		}
		clipboard = new java.util.ArrayList<>();
		for (Location l : src)
		{
			clipboard.add(new int[]{l.getId(), l.getType(), l.getOrientation(),
				l.getPosition().getX() - ax, l.getPosition().getY() - ay});
		}
		status.setText(" Copied " + clipboard.size() + " objects  ·  hover a tile and press Ctrl+V");
	}

	private void pasteSelection()
	{
		int px = hoverX >= 0 ? hoverX : (selX >= 0 ? selX : 0);
		int py = hoverX >= 0 ? hoverY : (selX >= 0 ? selY : 0);
		pasteAt(px, py);
	}

	/** Copy a single object (from its right-click menu) to the clipboard. */
	private void copyLocation(Location loc)
	{
		clipboard = new java.util.ArrayList<>();
		clipboard.add(new int[]{loc.getId(), loc.getType(), loc.getOrientation(), 0, 0});
		status.setText(" Copied object " + loc.getId()
			+ "  ·  right-click a tile → Paste here, or hover + Ctrl+V");
	}

	/** Paste the clipboard so its anchor lands on tile (px,py) of the current plane. */
	private void pasteAt(int px, int py)
	{
		if (clipboard == null || clipboard.isEmpty() || region == null)
		{
			return;
		}
		pushUndo();
		selectedLocs.clear();
		for (int[] c : clipboard)
		{
			int nx = Math.max(0, Math.min(63, px + c[3]));
			int ny = Math.max(0, Math.min(63, py + c[4]));
			Location nl = new Location(c[0], c[1], c[2],
				new net.runelite.cache.region.Position(nx, ny, plane));
			region.getLocations().getLocations().add(nl);
			selectedLocs.add(nl);
		}
		region.markDirty();
		sceneDirty = true;
		rerender();
		status.setText(" Pasted " + clipboard.size() + " object(s) at (" + px + "," + py + ")");
	}

	/** Small right-click menu offered on an empty tile when the clipboard has objects. */
	private void showTilePasteMenu(MouseEvent e, int x, int y)
	{
		if (clipboard == null || clipboard.isEmpty())
		{
			return;
		}
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem paste = new javax.swing.JMenuItem("Paste here (" + clipboard.size() + ")");
		paste.addActionListener(a -> pasteAt(x, y));
		menu.add(paste);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	// ---- terrain stamp (copy a patch of tiles, paste + rotate) ----------

	/** Copy the Area-fill selection's tiles (underlay/overlay/shape) into the reusable stamp. */
	private void copyTiles()
	{
		if (region == null)
		{
			return;
		}
		if (areaX0 < 0)
		{
			JOptionPane.showMessageDialog(this,
				"Turn on Area fill and drag a rectangle over the patch (e.g. a road/curve segment) first.",
				"Copy tiles", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		int x0 = Math.min(areaX0, areaX1), x1 = Math.max(areaX0, areaX1);
		int y0 = Math.min(areaY0, areaY1), y1 = Math.max(areaY0, areaY1);
		tileStampW = x1 - x0 + 1;
		tileStampH = y1 - y0 + 1;
		tileStamp = new java.util.ArrayList<>();
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				var t = region.getTile(plane, x, y);
				tileStamp.add(new int[]{x - x0, y - y0, t.underlayId & 0xFFFF,
					t.overlayId & 0xFFFF, t.overlayPath & 0xFF, t.overlayRotation & 3});
			}
		}
		status.setText(" Copied " + tileStamp.size() + " tiles (" + tileStampW + "×" + tileStampH
			+ ") — click 'Paste tiles', then click a tile to stamp; 'Rotate' turns it 90°");
	}

	/** Rotate the stamp 90° clockwise: remap tile positions AND each tile's shape rotation. */
	private void rotateTileStamp()
	{
		if (tileStamp == null || tileStamp.isEmpty())
		{
			return;
		}
		int w = tileStampW;
		java.util.List<int[]> rot = new java.util.ArrayList<>();
		for (int[] t : tileStamp)
		{
			// 90° CW (north-up): (dx,dy) -> (dy, w-1-dx); the shaped overlay rotates with it.
			rot.add(new int[]{t[1], w - 1 - t[0], t[2], t[3], t[4], (t[5] + 1) & 3});
		}
		tileStamp = rot;
		int tmp = tileStampW; tileStampW = tileStampH; tileStampH = tmp;
		updateStampPreview();
		if (show2D()) { canvas2D.repaint(); }
		status.setText(" Rotated shape 90° (" + tileStampW + "×" + tileStampH + ")");
	}

	/** Refresh the little "shape you're about to place" preview in the Shapes tab. */
	private void updateStampPreview()
	{
		if (stampPreview == null)
		{
			return;
		}
		if (tileStamp == null || tileStamp.isEmpty())
		{
			stampPreview.setIcon(null);
			return;
		}
		int w = 0, h = 0;
		for (int[] t : tileStamp) { w = Math.max(w, t[0] + 1); h = Math.max(h, t[1] + 1); }
		int[][] cells = new int[w][h];
		for (int[] row : cells) { java.util.Arrays.fill(row, -1); }
		for (int[] t : tileStamp) { cells[t[0]][t[1]] = t[4] * 4 + t[5]; }
		stampPreview.setIcon(cellsIcon(cells, 88));
	}

	/** Stamp the copied patch with its SW corner at tile (px,py) on the current plane. */
	private void stampTilesAt(int px, int py)
	{
		if (tileStamp == null || tileStamp.isEmpty() || region == null)
		{
			return;
		}
		pushUndo();
		for (int[] t : tileStamp)
		{
			int nx = px + t[0], ny = py + t[1];
			if (nx < 0 || nx > 63 || ny < 0 || ny > 63)
			{
				continue;
			}
			if (t[2] >= 0) // underlay = -1 means "leave the ground as-is" (presets keep the grass)
			{
				region.setUnderlay(plane, nx, ny, t[2]);
			}
			region.setOverlay(plane, nx, ny, t[3], t[4], t[5]);
		}
		sceneDirty = true;
		rerender();
		status.setText(" Stamped " + tileStamp.size() + " tiles at (" + px + "," + py
			+ ")  — click again to stamp more, or turn off 'Paste tiles'");
	}

	// ---- custom composite shapes (a real tile shape per cell) -----------

	/** Small icon of a custom shape: draws each non-empty cell's tile shape. cell = -1 or path*4+rot. */
	private javax.swing.Icon cellsIcon(int[][] cells, int px)
	{
		int w = cells.length, h = cells[0].length;
		int minx = w, maxx = -1, miny = h, maxy = -1;
		for (int x = 0; x < w; x++)
		{
			for (int y = 0; y < h; y++)
			{
				if (cells[x][y] >= 0) { minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y); }
			}
		}
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		if (maxx < 0)
		{
			return new javax.swing.ImageIcon(img);
		}
		int W = maxx - minx + 1, H = maxy - miny + 1;
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		double cell = Math.min((px - 2.0) / W, (px - 2.0) / H);
		double offx = (px - cell * W) / 2, offy = (px - cell * H) / 2;
		g.setColor(new Color(0x33, 0xaa, 0x38));
		for (int x = minx; x <= maxx; x++)
		{
			for (int y = miny; y <= maxy; y++)
			{
				int v = cells[x][y];
				if (v < 0) { continue; }
				int path = v / 4, rot = v % 4;
				double cx = offx + (x - minx) * cell, cy = offy + (H - 1 - (y - miny)) * cell; // y up
				if (path == 0)
				{
					g.fill(new java.awt.geom.Rectangle2D.Double(cx, cy, cell, cell));
				}
				else
				{
					double[][] poly = MapRenderer.shapePolygon(path, rot, true);
					if (poly != null)
					{
						java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
						for (int i = 0; i < poly.length; i++)
						{
							double vx = cx + poly[i][0] * cell, vy = cy + poly[i][1] * cell;
							if (i == 0) { p.moveTo(vx, vy); } else { p.lineTo(vx, vy); }
						}
						p.closePath();
						g.fill(p);
					}
				}
			}
		}
		g.dispose();
		return new javax.swing.ImageIcon(img);
	}

	/** Load a custom composite shape into the stamp and arm placement (click the map to stamp it). */
	private void loadCustomStamp(int[][] cells, String name)
	{
		if (region == null)
		{
			return;
		}
		int over = defToTileId(parse(overlayField, 0));
		setTool(Tool.OVERLAY);
		int w = cells.length, h = cells[0].length;
		int minx = w, maxx = -1, miny = h, maxy = -1;
		for (int x = 0; x < w; x++)
		{
			for (int y = 0; y < h; y++)
			{
				if (cells[x][y] >= 0) { minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y); }
			}
		}
		if (maxx < 0)
		{
			return;
		}
		tileStamp = new java.util.ArrayList<>();
		for (int x = minx; x <= maxx; x++)
		{
			for (int y = miny; y <= maxy; y++)
			{
				int v = cells[x][y];
				if (v < 0) { continue; }
				tileStamp.add(new int[]{x - minx, y - miny, -1, over, v / 4, v % 4});
			}
		}
		tileStampW = maxx - minx + 1;
		tileStampH = maxy - miny + 1;
		pasteTilesMode = true;
		updateStampPreview();
		status.setText(" '" + name + "' shape ready — click a tile to place it; 'Rotate' turns it, click again to place more");
	}

	// ---- custom shape builder + persistence -----------------------------

	private void loadCustomShapes()
	{
		if (!SHAPES_FILE.exists())
		{
			return;
		}
		try (java.io.Reader r = new java.io.FileReader(SHAPES_FILE))
		{
			CustomShape[] arr = new com.google.gson.Gson().fromJson(r, CustomShape[].class);
			customShapes.clear();
			if (arr != null)
			{
				for (CustomShape c : arr) { if (c != null && c.cells != null) { customShapes.add(c); } }
			}
		}
		catch (Exception ex)
		{
			System.err.println("Couldn't load custom shapes: " + ex.getMessage());
		}
	}

	private void saveCustomShapes()
	{
		try (java.io.Writer w = new java.io.FileWriter(SHAPES_FILE))
		{
			new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(customShapes, w);
		}
		catch (Exception ex)
		{
			System.err.println("Couldn't save custom shapes: " + ex.getMessage());
		}
	}

	/**
	 * The custom-shape builder: pick one of the default tile shapes (rotate it), then click cells
	 * in the grid to stamp that exact shape into each cell — so you compose your own multi-tile
	 * shape from the real building blocks. Right-click a cell clears it.
	 */
	private void openShapeBuilder() { openShapeBuilder(null); }

	/** If {@code editing} is non-null the builder opens pre-loaded with it and replaces it on save. */
	private void openShapeBuilder(CustomShape editing)
	{
		final int N = 10, CELL = 26;
		final int[][] cells = new int[N][N];
		for (int[] row : cells) { java.util.Arrays.fill(row, -1); }
		if (editing != null && editing.cells != null)
		{
			for (int x = 0; x < N && x < editing.cells.length; x++)
			{
				for (int y = 0; y < N && y < editing.cells[x].length; y++) { cells[x][y] = editing.cells[x][y]; }
			}
		}
		final int[] brush = {0, 0}; // path, rotation; path = -1 means the eraser

		final javax.swing.JLabel preview = new javax.swing.JLabel("", javax.swing.SwingConstants.CENTER);
		preview.setPreferredSize(new Dimension(96, 96));
		preview.setBorder(BorderFactory.createLineBorder(new Color(0x40, 0x44, 0x4a)));

		final JPanel gridPanel = new JPanel()
		{
			@Override protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				for (int x = 0; x < N; x++)
				{
					for (int y = 0; y < N; y++)
					{
						int px = x * CELL, py = (N - 1 - y) * CELL; // y up
						g2.setColor(new Color(0x2a, 0x2d, 0x32));
						g2.fillRect(px, py, CELL - 1, CELL - 1);
						int v = cells[x][y];
						if (v < 0) { continue; }
						int path = v / 4, rot = v % 4;
						g2.setColor(new Color(0x33, 0xaa, 0x38));
						if (path == 0)
						{
							g2.fillRect(px, py, CELL - 1, CELL - 1);
						}
						else
						{
							double[][] poly = MapRenderer.shapePolygon(path, rot, true);
							if (poly != null)
							{
								java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
								for (int i = 0; i < poly.length; i++)
								{
									double vx = px + poly[i][0] * (CELL - 1), vy = py + poly[i][1] * (CELL - 1);
									if (i == 0) { p.moveTo(vx, vy); } else { p.lineTo(vx, vy); }
								}
								p.closePath();
								g2.fill(p);
							}
						}
					}
				}
			}
		};
		gridPanel.setPreferredSize(new Dimension(N * CELL, N * CELL));
		java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter()
		{
			void apply(java.awt.event.MouseEvent e)
			{
				int x = e.getX() / CELL, y = N - 1 - (e.getY() / CELL);
				if (x < 0 || x >= N || y < 0 || y >= N) { return; }
				boolean erase = javax.swing.SwingUtilities.isRightMouseButton(e) || brush[0] < 0;
				cells[x][y] = erase ? -1 : (brush[0] * 4 + brush[1]);
				gridPanel.repaint();
				preview.setIcon(cellsIcon(cells, 88));
			}
			@Override public void mousePressed(java.awt.event.MouseEvent e) { apply(e); }
			@Override public void mouseDragged(java.awt.event.MouseEvent e) { apply(e); }
		};
		gridPanel.addMouseListener(ma);
		gridPanel.addMouseMotionListener(ma);
		preview.setIcon(cellsIcon(cells, 88));

		// Shape palette: the default tile shapes + an eraser; click to pick the brush, ↻ to rotate.
		final javax.swing.border.Border sel = BorderFactory.createLineBorder(new Color(0xFFCC33), 2);
		final JButton[] palette = new JButton[12];
		final JButton erase = new JButton("Erase");
		erase.setFocusable(false);
		erase.setToolTipText("Eraser — click cells to clear them");
		erase.addActionListener(e ->
		{
			brush[0] = -1;
			for (int i = 0; i < 12; i++) { palette[i].setBorder(null); }
			erase.setBorder(sel);
		});
		JPanel pal = new JPanel(new GridLayout(2, 6, 2, 2));
		for (int p = 0; p <= 11; p++)
		{
			final int path = p;
			JButton b = new JButton(shapeIcon(p, brush[1]));
			b.setMargin(new java.awt.Insets(1, 1, 1, 1));
			b.setFocusable(false);
			b.setToolTipText(p == 0 ? "Full tile" : "Shape " + p);
			b.addActionListener(e ->
			{
				brush[0] = path;
				erase.setBorder(null);
				for (int i = 0; i < 12; i++) { palette[i].setBorder(i == path ? sel : null); }
			});
			palette[p] = b;
			pal.add(b);
		}
		palette[0].setBorder(sel);
		JButton rot = new JButton("Rotate shape");
		rot.setFocusable(false);
		rot.addActionListener(e ->
		{
			brush[1] = (brush[1] + 1) & 3;
			for (int i = 0; i < 12; i++) { palette[i].setIcon(shapeIcon(i, brush[1])); }
		});

		javax.swing.JTextField name = new javax.swing.JTextField(editing != null ? editing.name : "My shape", 12);
		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		JLabel palLbl = new JLabel("1. Pick a shape (Erase clears cells), Rotate to turn it:"); palLbl.setAlignmentX(LEFT_ALIGNMENT);
		pal.setAlignmentX(LEFT_ALIGNMENT);
		JPanel palTools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		palTools.setAlignmentX(LEFT_ALIGNMENT);
		palTools.add(rot); palTools.add(erase);
		left.add(palLbl); left.add(pal); left.add(palTools);
		JLabel gridLbl = new JLabel("2. Click cells to place it (right-click clears):"); gridLbl.setAlignmentX(LEFT_ALIGNMENT);
		gridPanel.setAlignmentX(LEFT_ALIGNMENT);
		left.add(javax.swing.Box.createVerticalStrut(8)); left.add(gridLbl); left.add(gridPanel);

		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
		right.add(new JLabel("Preview:"));
		right.add(preview);
		JPanel np = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
		np.add(new JLabel("Name:")); np.add(name);
		right.add(np);

		JPanel content = new JPanel(new java.awt.BorderLayout(12, 6));
		content.add(left, java.awt.BorderLayout.CENTER);
		content.add(right, java.awt.BorderLayout.EAST);

		int res = JOptionPane.showConfirmDialog(this, content, "Build a custom tile shape",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (res != JOptionPane.OK_OPTION)
		{
			return;
		}
		boolean any = false;
		for (int[] row : cells) { for (int v : row) { if (v >= 0) { any = true; } } }
		if (!any)
		{
			return;
		}
		CustomShape cs = editing != null ? editing : new CustomShape();
		cs.name = name.getText().trim().isEmpty() ? "Custom" : name.getText().trim();
		cs.cells = cells;
		if (editing == null) { customShapes.add(cs); }
		saveCustomShapes();
		rebuildCustomShapesRow();
	}

	private void deleteSelection()
	{
		if (selectedLocs.isEmpty())
		{
			return;
		}
		pushUndo();
		region.getLocations().getLocations().removeAll(selectedLocs);
		region.markDirty();
		int n = selectedLocs.size();
		selectedLocs.clear();
		sceneDirty = true;
		rerender();
		status.setText(" Deleted " + n + " objects");
	}

	private void dragMove2D(MouseEvent e)
	{
		int[] t = tile2D(e);
		if (t == null)
		{
			return;
		}
		if (drag2DLoc != null)
		{
			Location nl = new Location(drag2DLoc.getId(), drag2DLoc.getType(), drag2DLoc.getOrientation(),
				new net.runelite.cache.region.Position(t[0], t[1], plane));
			replaceLocation(drag2DLoc, nl);
			drag2DLoc = selectedLoc = nl;
			sceneBuilder.setHighlight(nl);
			render2D(); // keep drag smooth; rebuild 3D on release
		}
		else if (drag2DNpc != null)
		{
			drag2DNpc = selectedNpc = service.replaceSpawn(drag2DNpc,
				region.getBaseX() + t[0], region.getBaseY() + t[1], plane, drag2DNpc.orientation);
			render2D();
		}
	}

	private void endDrag2D()
	{
		drag2DLoc = null;
		drag2DNpc = null;
		sceneDirty = true;
		rerender();
		updateInspector();
	}

	/** Live drag-move of the grabbed object in the 3D view (like 2D), throttled to tile changes. */
	private void dragMove3D(MouseEvent e)
	{
		if (drag3DLoc == null)
		{
			return;
		}
		int[] t = pickTile(e.getX(), e.getY());
		if (t == null || (lastMoveTile != null && lastMoveTile[0] == t[0] && lastMoveTile[1] == t[1]))
		{
			return;
		}
		lastMoveTile = t;
		if (!drag3DMoved)
		{
			pushUndo();
			drag3DMoved = true;
		}
		Location nl = new Location(drag3DLoc.getId(), drag3DLoc.getType(), drag3DLoc.getOrientation(),
			new net.runelite.cache.region.Position(t[0], t[1], plane));
		replaceLocation(drag3DLoc, nl);
		drag3DLoc = selectedLoc = nl;
		sceneBuilder.setHighlight(nl);
		sceneDirty = true;
		render3DFull();
	}

	private void endDrag3D()
	{
		boolean moved = drag3DMoved;
		drag3DLoc = null;
		lastMoveTile = null;
		drag3DMoved = false;
		if (moved)
		{
			sceneDirty = true;
			rerender();
			updateInspector();
		}
	}

	private void rightClick2D(MouseEvent e)
	{
		int[] t = tile2D(e);
		if (t == null)
		{
			return;
		}
		Location loc = topObjectAt(t[0], t[1]);
		SpawnLoader.Spawn npc = showSpawns
			? service.spawnAt(region.getBaseX() + t[0], region.getBaseY() + t[1], plane) : null;
		if (npc != null && !e.isAltDown())
		{
			selectNpc(npc);
			showNpcMenu(e, npc);
			return;
		}
		if (loc != null)
		{
			selectLoc(loc);
			showObjectMenu(e, loc);
			return;
		}
		if (npc != null)
		{
			selectNpc(npc);
			showNpcMenu(e, npc);
			return;
		}
		int[] ss = serverSpawnAt(region.getBaseX() + t[0], region.getBaseY() + t[1], plane);
		if (ss != null)
		{
			showServerSpawnMenu(e, ss);
			return;
		}
		showTilePasteMenu(e, t[0], t[1]); // empty tile: offer Paste if the clipboard has objects
	}

	/** The server spawn (from getServerSpawns) at a world tile on the current plane, or null. */
	private int[] serverSpawnAt(int worldX, int worldY, int plane)
	{
		if (!renderOptions.showServerSpawns)
		{
			return null;
		}
		for (int[] s : service.getServerSpawns())
		{
			if (s[1] == worldX && s[2] == worldY && s[3] == plane)
			{
				return s;
			}
		}
		return null;
	}

	/** Right-click menu for a server-spawned (HomeHandler) object: shows its id and copy options. */
	private void showServerSpawnMenu(MouseEvent e, int[] s)
	{
		int id = s[0], x = s[1], y = s[2], z = s[3], type = s[4], rot = s[5];
		ObjectDefinition def = service.getObject(id);
		String name = def != null && def.getName() != null && !def.getName().equalsIgnoreCase("null")
			? def.getName() : "(no name)";
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem info = new javax.swing.JMenuItem("Server obj " + id + "  " + name);
		info.setEnabled(false);
		menu.add(info);
		javax.swing.JMenuItem pos = new javax.swing.JMenuItem(
			"at " + x + ", " + y + ", " + z + "   type " + type + "  rot " + rot + "   (HomeHandler)");
		pos.setEnabled(false);
		menu.add(pos);
		menu.addSeparator();
		javax.swing.JMenuItem copyId = new javax.swing.JMenuItem("Copy id  (" + id + ")");
		copyId.addActionListener(a -> copyText(String.valueOf(id)));
		menu.add(copyId);
		String spawnLine = "GameObject.spawn(" + id + ", " + x + ", " + y + ", " + z + ", " + type + ", " + rot + ");";
		javax.swing.JMenuItem copyLine = new javax.swing.JMenuItem("Copy spawn line");
		copyLine.setToolTipText(spawnLine);
		copyLine.addActionListener(a -> copyText(spawnLine));
		menu.add(copyLine);
		menu.show(canvas2D, e.getX(), e.getY());
	}

	/** Copies text to the system clipboard and notes it in the status bar. */
	private void copyText(String s)
	{
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new java.awt.datatransfer.StringSelection(s), null);
			status.setText(" Copied: " + s);
		}
		catch (Exception ex)
		{
			status.setText(" Copy failed: " + ex.getMessage());
		}
	}

	private void selectNpc(SpawnLoader.Spawn npc)
	{
		clearSelection();
		selectedNpc = npc;
		String kind = npc.npc ? "NPC" : "Object";
		String defName = npc.npc
			? (service.getNpc(npc.id) != null ? service.getNpc(npc.id).getName() : (npc.name == null ? "?" : npc.name))
			: (service.getObject(npc.id) != null && service.getObject(npc.id).getName() != null
				&& !service.getObject(npc.id).getName().equalsIgnoreCase("null")
				? service.getObject(npc.id).getName() : "(no name)");
		inspector.removeAll();
		inspector.add(new JLabel(kind + " spawn  " + npc.id));
		inspector.add(new JLabel(defName));
		inspector.add(new JLabel((npc.npc ? "facing " : "type " + npc.type + ", rot ")
			+ "SWNE".charAt(npc.orientation & 3) + "  at (" + (npc.x & 63) + "," + (npc.y & 63) + ")"));
		JButton rotB = new JButton("Rotate 90°");
		rotB.addActionListener(a -> rotateSelectedNpc());
		inspector.add(rotB);
		JButton delB = new JButton("Delete");
		delB.addActionListener(a -> deleteSelectedNpc());
		inspector.add(delB);
		inspector.revalidate();
		inspector.repaint();
		status.setText(" Selected " + kind.toLowerCase() + " spawn " + npc.id
			+ " — press X or R to rotate, drag to move, Del to remove");
		canvas2D.repaint();
	}

	private void clearNpcSelection()
	{
		selectedNpc = null;
	}

	private void showNpcMenu(MouseEvent e, SpawnLoader.Spawn npc)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		String defName = npc.npc
			? (service.getNpc(npc.id) != null ? service.getNpc(npc.id).getName() : "")
			: (service.getObject(npc.id) != null ? service.getObject(npc.id).getName() : "");
		javax.swing.JMenuItem info = new javax.swing.JMenuItem(
			(npc.npc ? "NPC " : "Object ") + npc.id + "  " + (defName == null ? "" : defName));
		info.setEnabled(false);
		menu.add(info);
		menu.addSeparator();
		javax.swing.JMenuItem rot = new javax.swing.JMenuItem("Rotate 90°");
		rot.addActionListener(a -> rotateSelectedNpc());
		menu.add(rot);
		javax.swing.JMenuItem del = new javax.swing.JMenuItem("Delete");
		del.addActionListener(a -> deleteSelectedNpc());
		menu.add(del);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void rotateSelectedNpc()
	{
		if (selectedNpc == null)
		{
			return;
		}
		selectedNpc = service.replaceSpawn(selectedNpc, selectedNpc.x, selectedNpc.y, selectedNpc.z,
			(selectedNpc.orientation + 1) & 3);
		sceneDirty = true;
		rerender();
		selectNpc(selectedNpc);
	}

	private void deleteSelectedNpc()
	{
		if (selectedNpc == null)
		{
			return;
		}
		boolean wasNpc = selectedNpc.npc;
		service.removeSpawn(selectedNpc);
		selectedNpc = null;
		sceneDirty = true;
		rerender();
		updateInspector();
		status.setText(wasNpc ? " Removed NPC spawn" : " Removed object spawn");
	}

	private void selectLoc(Location loc)
	{
		clearNpcSelection();
		selectedLocs.clear();
		selectedLoc = loc;
		sceneBuilder.setHighlight(loc);
		sceneDirty = true;
		rerender();
		net.runelite.cache.definitions.ObjectDefinition def = service.getObject(loc.getId());
		String name = def != null ? def.getName() : "?";
		inspector.removeAll();
		inspector.add(new JLabel("Object " + loc.getId()));
		inspector.add(new JLabel(name));
		inspector.add(new JLabel("type " + loc.getType() + "  rot " + loc.getOrientation()));
		inspector.add(new JLabel("at (" + loc.getPosition().getX() + "," + loc.getPosition().getY() + ")"));
		JButton rotB = new JButton("Rotate 90°");
		rotB.addActionListener(a -> rotateLocation(loc));
		inspector.add(rotB);
		JButton movB = new JButton("Move");
		movB.addActionListener(a -> { movingLoc = loc; status.setText(" Move: left-click a tile"); });
		inspector.add(movB);
		JButton delB = new JButton("Delete");
		delB.addActionListener(a -> deleteLocation(loc));
		inspector.add(delB);
		// live model view of the selected object (like the official editor)
		try
		{
			double[] info = new double[2];
			JLabel pv = new JLabel(renderPreview(sceneBuilder.buildModelPreview(loc.getId(), info), info));
			pv.setAlignmentX(LEFT_ALIGNMENT);
			inspector.add(pv);
		}
		catch (Exception ignored)
		{
		}
		inspector.revalidate();
		inspector.repaint();
		showObjectInTab(loc.getId()); // reveal the clicked object in the Objects tab
		pulseOn = true;
		pulseTimer.restart(); // flash every same-id object in 2D
		canvas2D.repaint();
	}

	/**
	 * Switches the right-hand palette to the Objects tab and shows the given object's preview +
	 * per-model buttons — WITHOUT arming placement (so selecting/inspecting doesn't change the tool).
	 */
	private void showObjectInTab(int id)
	{
		if (sideTabs == null) { return; }
		int idx = sideTabs.indexOfTab("Objects");
		if (idx >= 0) { sideTabs.setSelectedIndex(idx); }
		net.runelite.cache.definitions.ObjectDefinition def = service.getObject(id);
		String name = def != null && def.getName() != null ? def.getName() : "";
		ObjEntry e = new ObjEntry(id, name);
		previewObjectInto(e, objectPreview);
		rebuildTypeButtonsInto(e, typeButtonRow, typeButtonList, objectPreview);
	}

	private void clearSelection()
	{
		if (selectedLoc == null)
		{
			return;
		}
		selectedLoc = null;
		sceneBuilder.setHighlight(null);
		pulseTimer.stop();
		pulseOn = false;
		sceneDirty = true;
		rerender();
		updateInspector();
	}

	private void replaceLocation(Location oldLoc, Location newLoc)
	{
		region.getLocations().getLocations().remove(oldLoc);
		region.getLocations().getLocations().add(newLoc);
		region.markDirty();
	}

	private void rotateLocation(Location loc)
	{
		pushUndo();
		Location nl = new Location(loc.getId(), loc.getType(), (loc.getOrientation() + 1) & 3, loc.getPosition());
		replaceLocation(loc, nl);
		selectLoc(nl);
	}

	private void moveLocationTo(Location loc, int x, int y)
	{
		Location nl = new Location(loc.getId(), loc.getType(), loc.getOrientation(),
			new net.runelite.cache.region.Position(x, y, plane));
		replaceLocation(loc, nl);
		selectLoc(nl);
	}

	private void deleteLocation(Location loc)
	{
		pushUndo();
		region.getLocations().getLocations().remove(loc);
		region.markDirty();
		selectedLoc = null;
		sceneBuilder.setHighlight(null);
		pulseTimer.stop();
		pulseOn = false;
		sceneDirty = true;
		rerender();
		updateInspector();
		status.setText(" Deleted object " + loc.getId());
	}

	private void tickMovement()
	{
		if (!show3D() || keysDown.isEmpty())
		{
			moveTimer.stop();
			if (show3D())
			{
				render3DFull(); // sharpen once movement stops
			}
			return;
		}
		double step = moveStep;
		double sinY = Math.sin(camera.yaw), cosY = Math.cos(camera.yaw);
		if (keysDown.contains(java.awt.event.KeyEvent.VK_W)) { camera.cx += sinY * step; camera.cz += cosY * step; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_S)) { camera.cx -= sinY * step; camera.cz -= cosY * step; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_D)) { camera.cx += cosY * step; camera.cz -= sinY * step; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_A)) { camera.cx -= cosY * step; camera.cz += sinY * step; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_Q)) { camera.cy -= step; }       // up
		if (keysDown.contains(java.awt.event.KeyEvent.VK_E)) { camera.cy += step; }       // down
		// Arrow keys rotate (orbit) the camera, same as middle-drag.
		double rot = 0.035;
		if (keysDown.contains(java.awt.event.KeyEvent.VK_LEFT))  { camera.yaw -= rot; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_RIGHT)) { camera.yaw += rot; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_UP))    { camera.pitch -= rot; }
		if (keysDown.contains(java.awt.event.KeyEvent.VK_DOWN))  { camera.pitch += rot; }
		camera.pitch = Math.max(0.15, Math.min(1.55, camera.pitch));
		// Keep the look-at point anchored near the terrain. Without this, holding E (down)
		// pushes the focus far below the ground until it passes behind the camera and the
		// view "detaches" and swings wildly. Clamp to a sane band around the map heights.
		camera.cy = Math.max(-15000, Math.min(600, camera.cy));
		render3DFast();
	}

	private void centerCamera()
	{
		camera.cx = 32 * 128;
		camera.cz = 32 * 128;
		camera.cy = -400;
		camera.distance = 9000;
		camera.yaw = 0.0;   // face north so the 3D view lines up with the north-up 2D map
		camera.pitch = 1.0;
		camera.zoom = 1.0;
	}

	private void toggle3D(boolean on)
	{
		if (splitMode)
		{
			threeD = on; // remembered for when split turns off
			return;
		}
		threeD = on;
		updateCenter();
		if (on)
		{
			sceneDirty = true;
			fit3DToViewport();
			rerender();
			canvas3D.requestFocusInWindow(); // so WASD reaches the canvas
			status.setText(" 3D — middle-drag / arrows: rotate · wheel: zoom · WASD/QE: move · N: face north · left-click: select · Alt-drag: move object · right-click: edit");
			if (animating)
			{
				animTimer.start();
			}
		}
		else
		{
			keysDown.clear();
			animTimer.stop();
			javax.swing.SwingUtilities.invokeLater(this::fit2D);
		}
	}

	/**
	 * Force the split divider to the middle once the pane actually has a size. Uses an
	 * int divider location computed from the real width/height (the proportional
	 * setDividerLocation(double) silently no-ops before the pane is laid out, which is
	 * what made one side collapse to "full 3D"). Double-deferred so layout settles first.
	 */
	private void resetDivider()
	{
		needDividerReset = true; // belt-and-braces: the resize listener also handles it
		Runnable centre = () ->
		{
			if (!splitMode)
			{
				return;
			}
			int size = splitHorizontal ? splitPane.getWidth() : splitPane.getHeight();
			if (size > 60)
			{
				splitPane.setDividerLocation((size - splitPane.getDividerSize()) / 2);
				needDividerReset = false;
			}
		};
		javax.swing.SwingUtilities.invokeLater(() -> javax.swing.SwingUtilities.invokeLater(() ->
		{
			centre.run();
			fit3DToViewport();
			fit2D();
			if (show3D())
			{
				render3DFull();
			}
			// Re-assert once more after the (slow) render + any relayout has settled —
			// this is the moment the side-by-side split was collapsing.
			javax.swing.Timer t = new javax.swing.Timer(300, ev -> centre.run());
			t.setRepeats(false);
			t.start();
		}));
	}

	private void toggleSplit(boolean on)
	{
		splitMode = on;
		updateCenter();
		if (on)
		{
			sceneDirty = true;
			resetDivider();
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				canvas3D.requestFocusInWindow();
				if (animating)
				{
					animTimer.start();
				}
			});
			status.setText(" Split view — drag the divider to resize.");
		}
		else
		{
			keysDown.clear();
			if (!threeD)
			{
				animTimer.stop();
			}
			rerender();
		}
	}

	/** Size the 3D renderers to the current 3D canvas so it fills its area. */
	private void fit3DToViewport()
	{
		int w = canvas3D.getWidth();
		int h = canvas3D.getHeight();
		if (w < 80 || h < 80)
		{
			return; // not laid out yet; the component-resize listener will refit
		}
		if (w != view3dW || h != view3dH)
		{
			view3dW = w;
			view3dH = h;
			renderer3D = new Renderer3D(w, h, 0x202830);
			renderer3DFast = new Renderer3D(Math.max(1, (int) (w * FAST_SCALE)), Math.max(1, (int) (h * FAST_SCALE)), 0x202830);
			pickRenderer = new Renderer3D(w, h, 0x202830);
		}
	}

	private void setStatusBusy(String msg)
	{
		status.setText(" " + msg);
		status.paintImmediately(status.getBounds());
	}

	private void updateStatus()
	{
		String dirty = region != null && region.isDirty() ? "  *unsaved*" : "";
		String sel = selX >= 0 ? "  tile local(" + selX + "," + selY + ") world("
			+ (region.getBaseX() + selX) + "," + (region.getBaseY() + selY) + ")" : "";
		status.setText(" plane " + plane + sel + dirty);
	}

	private void updateInspector()
	{
		inspector.removeAll();
		if (region != null && selX >= 0)
		{
			Tile t = region.getTile(plane, selX, selY);
			inspector.add(new JLabel("local " + selX + "," + selY + "  plane " + plane));
			inspector.add(new JLabel("underlay: " + (t.underlayId & 0xFFFF)));
			inspector.add(new JLabel("overlay: " + (t.overlayId & 0xFFFF)
				+ " path " + (t.overlayPath & 0xFF) + " rot " + (t.overlayRotation & 0x3)));
			inspector.add(new JLabel("height: " + (t.height == null ? "auto" : t.height)));
			inspector.add(new JLabel("settings: " + (t.settings & 0xFF)));

			List<Location> here = region.locationsAt(plane, selX, selY);
			inspector.add(new JLabel(here.size() + " object(s):"));
			for (Location loc : here)
			{
				ObjectDefinition def = service.getObject(loc.getId());
				String name = def != null ? def.getName() : "?";
				JPanel rowp = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
				rowp.add(new JLabel(loc.getId() + " " + name + " t" + loc.getType() + " r" + loc.getOrientation()));
				JButton del = new JButton("x");
				del.setMargin(new java.awt.Insets(0, 4, 0, 4));
				del.addActionListener(e -> { region.removeLocation(loc); rerender(); updateInspector(); });
				rowp.add(del);
				inspector.add(rowp);
			}
		}
		inspector.revalidate();
		inspector.repaint();
	}

	// ---- editing actions ----------------------------------------------

	private interface TileOp { void apply(int x, int y); }

	private void applyTool(int x, int y)
	{
		if (region == null)
		{
			return;
		}
		try
		{
			switch (tool)
			{
				case SELECT:
					selX = x; selY = y;
					updateInspector();
					break;
				case UNDERLAY:
				case OVERLAY:
				case HEIGHT:
				case SETTINGS:
					if (tool == Tool.HEIGHT)
					{
						strokeHeights = null; // recompute effective heights for this application
					}
					forBrush(x, y, this::applyTerrainAt);
					break;
				case PLACE_OBJECT:
					if (!region.hasLocationArchive)
					{
						warnNoKey();
						return;
					}
					region.addLocation(parse(objectIdField, 0), parse(objectTypeField, 10),
						parse(objectRotField, 0), plane, x, y);
					break;
				case PLACE_NPC:
					if (placeNpcId < 0)
					{
						status.setText(" Pick an NPC first (NPCs tab → Use for Place NPC)");
						return;
					}
					service.addNpcSpawn(placeNpcId, placeNpcName,
						region.getBaseX() + x, region.getBaseY() + y, plane, placeNpcDir);
					showSpawns = true;
					if (spawnsBox != null)
					{
						spawnsBox.setSelected(true);
					}
					status.setText(" Placed NPC " + placeNpcId + " — Save writes editor_spawns.json");
					break;
				case DELETE_OBJECT:
					if (service.removeSessionNpcSpawnAt(region.getBaseX() + x, region.getBaseY() + y, plane))
					{
						status.setText(" Removed placed NPC spawn");
						break;
					}
					if (!region.hasLocationArchive)
					{
						warnNoKey();
						return;
					}
					List<Location> here = region.locationsAt(plane, x, y);
					if (!here.isEmpty())
					{
						region.removeLocation(here.get(here.size() - 1));
					}
					break;
				default:
			}
			if (tool != Tool.SELECT)
			{
				selX = x; selY = y;
				sceneDirty = true; // terrain/objects changed; rebuild 3D next time
			}
			updateSelCorners();
			rerender();
			updateInspector();
		}
		catch (NumberFormatException ex)
		{
			status.setText(" Invalid number in a parameter field");
		}
	}

	// ---- undo / redo -----------------------------------------------------

	private Snapshot snapshot()
	{
		Tile[][][] src = region.getMap().getTiles();
		Tile[][][] copy = new Tile[4][64][64];
		for (int z = 0; z < 4; z++)
		{
			for (int x = 0; x < 64; x++)
			{
				for (int y = 0; y < 64; y++)
				{
					Tile s = src[z][x][y];
					Tile d = new Tile();
					if (s != null)
					{
						d.height = s.height;
						d.attrOpcode = s.attrOpcode;
						d.settings = s.settings;
						d.overlayId = s.overlayId;
						d.overlayPath = s.overlayPath;
						d.overlayRotation = s.overlayRotation;
						d.underlayId = s.underlayId;
					}
					copy[z][x][y] = d;
				}
			}
		}
		return new Snapshot(copy, new java.util.ArrayList<>(region.getLocations().getLocations()));
	}

	private void restore(Snapshot s)
	{
		Tile[][][] dst = region.getMap().getTiles();
		for (int z = 0; z < 4; z++)
		{
			for (int x = 0; x < 64; x++)
			{
				for (int y = 0; y < 64; y++)
				{
					Tile d = dst[z][x][y];
					Tile c = s.tiles[z][x][y];
					d.height = c.height;
					d.attrOpcode = c.attrOpcode;
					d.settings = c.settings;
					d.overlayId = c.overlayId;
					d.overlayPath = c.overlayPath;
					d.overlayRotation = c.overlayRotation;
					d.underlayId = c.underlayId;
				}
			}
		}
		region.getLocations().getLocations().clear();
		region.getLocations().getLocations().addAll(s.locs);
		region.markDirty();
		selectedLoc = null;
		movingLoc = null;
		sceneBuilder.setHighlight(null);
		pulseTimer.stop();
		sceneDirty = true;
		updateSelCorners();
		rerender();
		updateInspector();
	}

	private void pushUndo()
	{
		if (region == null)
		{
			return;
		}
		redoStack.clear();
		undoStack.push(snapshot());
		while (undoStack.size() > UNDO_LIMIT)
		{
			undoStack.removeLast();
		}
	}

	private void undo()
	{
		if (region == null || undoStack.isEmpty())
		{
			status.setText(" Nothing to undo");
			return;
		}
		redoStack.push(snapshot());
		restore(undoStack.pop());
		status.setText(" Undo (" + undoStack.size() + " left)");
	}

	private void redo()
	{
		if (region == null || redoStack.isEmpty())
		{
			status.setText(" Nothing to redo");
			return;
		}
		undoStack.push(snapshot());
		restore(redoStack.pop());
		status.setText(" Redo");
	}

	// ---- minimap ---------------------------------------------------------

	private JComponent buildMinimap()
	{
		minimap = new JComponent()
		{
			@Override protected void paintComponent(Graphics g)
			{
				g.setColor(new Color(0x1A1C20));
				g.fillRect(0, 0, getWidth(), getHeight());
				if (minimapImage == null)
				{
					return;
				}
				int mx = (getWidth() - minimapImage.getWidth()) / 2;
				int my = (getHeight() - minimapImage.getHeight()) / 2;
				g.drawImage(minimapImage, mx, my, null);
				if (show2D())
				{
					java.awt.Rectangle vr = canvasScroll.getViewport().getViewRect();
					double s = minimapImage.getWidth() / (64.0 * tileSize);
					int ox = Math.max(0, (canvas2D.getPreferredSize().width - image2DSize()) / 2) + neighbor2DStrip();
					int oy = Math.max(0, (canvas2D.getPreferredSize().height - image2DSize()) / 2) + neighbor2DStrip();
					g.setColor(Color.WHITE);
					g.drawRect(mx + (int) ((vr.x - ox) * s), my + (int) ((vr.y - oy) * s),
						(int) (vr.width * s), (int) (vr.height * s));
				}
				// 3D camera-centre marker (yellow crosshair).
				if (show3D())
				{
					double per = minimapImage.getWidth() / 64.0;
					int cxp = mx + (int) (camera.cx / 128.0 * per);
					int cyp = my + (int) ((63 - camera.cz / 128.0) * per);
					g.setColor(Color.YELLOW);
					((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(2f));
					g.drawLine(cxp - 6, cyp, cxp + 6, cyp);
					g.drawLine(cxp, cyp - 6, cxp, cyp + 6);
				}
			}
		};
		minimap.setPreferredSize(new Dimension(320, 268));
		minimap.addMouseListener(new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent e) { navigateMinimap(e); }
		});
		minimap.addMouseMotionListener(new MouseAdapter()
		{
			@Override public void mouseDragged(MouseEvent e) { navigateMinimap(e); }
		});
		canvasScroll.getViewport().addChangeListener(e -> minimap.repaint());
		return minimap;
	}

	private void navigateMinimap(MouseEvent e)
	{
		if (minimapImage == null)
		{
			return;
		}
		int mx = (minimap.getWidth() - minimapImage.getWidth()) / 2;
		int my = (minimap.getHeight() - minimapImage.getHeight()) / 2;

		// Scroll the 2D view to the clicked point.
		if (show2D())
		{
			double s = 64.0 * tileSize / minimapImage.getWidth();
			int ox = Math.max(0, (canvas2D.getPreferredSize().width - image2DSize()) / 2) + neighbor2DStrip();
			int oy = Math.max(0, (canvas2D.getPreferredSize().height - image2DSize()) / 2) + neighbor2DStrip();
			javax.swing.JViewport vp = canvasScroll.getViewport();
			int nx = ox + (int) ((e.getX() - mx) * s) - vp.getWidth() / 2;
			int ny = oy + (int) ((e.getY() - my) * s) - vp.getHeight() / 2;
			nx = Math.max(0, Math.min(nx, Math.max(0, canvas2D.getPreferredSize().width - vp.getWidth())));
			ny = Math.max(0, Math.min(ny, Math.max(0, canvas2D.getPreferredSize().height - vp.getHeight())));
			vp.setViewPosition(new java.awt.Point(nx, ny));
		}

		// Recentre the 3D camera on the clicked tile.
		if (show3D())
		{
			double per = minimapImage.getWidth() / 64.0; // pixels per tile
			double tx = Math.max(0, Math.min(63, (e.getX() - mx) / per));
			double ty = Math.max(0, Math.min(63, 63 - (e.getY() - my) / per)); // north up
			camera.cx = tx * 128;
			camera.cz = ty * 128;
			render3DFull();
			minimap.repaint();
		}
	}

	// ---- interactive height ----------------------------------------------

	/** Effective stored height byte for the tile, even when it's auto (null). */
	private int effectiveStoredHeight(int x, int y)
	{
		if (strokeHeights == null)
		{
			strokeHeights = new net.runelite.cache.region.Region(region.getRegionId());
			strokeHeights.loadTerrain(region.getMap());
		}
		int h = plane == 0
			? -strokeHeights.getTileHeight(0, x, y)
			: strokeHeights.getTileHeight(plane - 1, x, y) - strokeHeights.getTileHeight(plane, x, y);
		return Math.max(0, Math.min(255, h / 8));
	}

	/**
	 * Absolute world height of a tile in the same units the "Tile heights" overlay shows
	 * (-worldY/8, higher = larger). Used so the fixed-height field is plane-independent.
	 */
	private int worldHeightUnits(int p, int x, int y)
	{
		if (strokeHeights == null)
		{
			strokeHeights = new net.runelite.cache.region.Region(region.getRegionId());
			strokeHeights.loadTerrain(region.getMap());
		}
		return (int) Math.round(-strokeHeights.getTileHeight(p, x, y) / 8.0);
	}

	/**
	 * Set one height vertex (cx,cy) to an absolute world height v (Tile-heights units), plane
	 * aware: on plane 0 the stored byte equals v; on upper planes it's relative to the plane
	 * below. Out-of-range vertices (the far edge of the region) are skipped.
	 */
	private void setAbsoluteCornerHeight(int p, int cx, int cy, int v)
	{
		if (cx < 0 || cy < 0 || cx > 63 || cy > 63)
		{
			return;
		}
		int b = p == 0 ? v : v - worldHeightUnits(p - 1, cx, cy);
		region.setHeight(p, cx, cy, Math.max(0, Math.min(255, b)));
	}

	/**
	 * Level the current plane (or the selected area) to a single flat height, so an
	 * upper floor becomes an even surface to build on instead of inheriting the bumpy
	 * ground below. Writes the per-tile height bytes needed to hit that flat level.
	 */
	private void flattenPlane(boolean areaOnly)
	{
		if (region == null)
		{
			return;
		}
		int x0 = 0, y0 = 0, x1 = 63, y1 = 63;
		if (areaOnly)
		{
			if (areaX0 < 0)
			{
				JOptionPane.showMessageDialog(this,
					"No area selected. Turn on Area fill and drag a rectangle on the 2D map first.",
					"Flatten area", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			x0 = Math.min(areaX0, areaX1); x1 = Math.max(areaX0, areaX1);
			y0 = Math.min(areaY0, areaY1); y1 = Math.max(areaY0, areaY1);
		}

		net.runelite.cache.region.Region r = new net.runelite.cache.region.Region(region.getRegionId());
		r.loadTerrain(region.getMap());

		// Target = the current average height over the region being flattened.
		long sum = 0;
		int n = 0;
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				sum += r.getTileHeight(plane, x, y);
				n++;
			}
		}
		int flat = (int) (sum / n);

		if (JOptionPane.showConfirmDialog(this,
			"Flatten " + (areaOnly ? "the selected area" : "the whole plane " + plane)
				+ " to one level?\nThis rewrites the height of " + n + " tiles (undoable).",
			"Flatten plane " + plane, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
		{
			return;
		}

		pushUndo();
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				int b;
				if (plane == 0)
				{
					b = Math.round(-flat / 8f);
				}
				else
				{
					// stored byte = (height of plane below - target) / 8
					b = Math.round((r.getTileHeight(plane - 1, x, y) - flat) / 8f);
				}
				b = Math.max(0, Math.min(255, b));
				if (b == 1)
				{
					b = 2; // the client treats a stored height of 1 as 0
				}
				region.setHeight(plane, x, y, b);
			}
		}
		rebuildPlaneGrid();
		sceneDirty = true;
		rerender();
		status.setText(" Flattened plane " + plane + " (" + n + " tiles) to one level.");
	}

	/**
	 * Smooth overlay edges — replaces the blocky staircase of full-tile overlays with
	 * diagonal cuts (like the official editor's smoothing). For each overlay tile whose
	 * two adjacent orthogonal neighbours are NOT overlay (an outer/convex corner), the
	 * tile is cut with a diagonal half so that corner reads as a clean 45° edge; concave
	 * notches (an empty tile with two adjacent overlay neighbours) are filled with the
	 * matching diagonal so inner curves round off too.
	 */
	private void smoothOverlay(boolean areaOnly)
	{
		if (region == null)
		{
			return;
		}
		int x0 = 0, y0 = 0, x1 = 63, y1 = 63;
		if (areaOnly)
		{
			if (areaX0 < 0)
			{
				JOptionPane.showMessageDialog(this,
					"No area selected. Turn on Area fill and drag a rectangle over the river/overlay first.",
					"Smooth overlay", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			x0 = Math.min(areaX0, areaX1); x1 = Math.max(areaX0, areaX1);
			y0 = Math.min(areaY0, areaY1); y1 = Math.max(areaY0, areaY1);
		}

		// Snapshot overlay presence + id for the whole plane (neighbours may be outside area).
		boolean[][] has = new boolean[64][64];
		int[][] oid = new int[64][64];
		for (int x = 0; x < 64; x++)
		{
			for (int y = 0; y < 64; y++)
			{
				int o = region.getTile(plane, x, y).overlayId & 0xFFFF;
				has[x][y] = o != 0;
				oid[x][y] = o;
			}
		}

		pushUndo();
		int changed = 0;
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				boolean n = y < 63 && has[x][y + 1];
				boolean s = y > 0 && has[x][y - 1];
				boolean e = x < 63 && has[x + 1][y];
				boolean w = x > 0 && has[x - 1][y];

				if (has[x][y])
				{
					// Convex corner: two adjacent orthogonal neighbours are land.
					// path 1 (diagonal halves); rotation empties the land-facing corner:
					// rot0=NE, rot1=SE, rot2=SW, rot3=NW.
					int rot = -1;
					if (!n && !e && s && w) { rot = 0; }
					else if (!s && !e && n && w) { rot = 1; }
					else if (!s && !w && n && e) { rot = 2; }
					else if (!n && !w && s && e) { rot = 3; }
					if (rot >= 0)
					{
						region.setOverlay(plane, x, y, oid[x][y], 1, rot);
						changed++;
					}
					else if ((n ? 1 : 0) + (s ? 1 : 0) + (e ? 1 : 0) + (w ? 1 : 0) <= 1)
					{
						// Lone poke: an overlay tile with 3-4 land neighbours — a blocky
						// single-tile nub sticking out. Remove it so the outline reads clean.
						region.setOverlay(plane, x, y, 0, 0, 0);
						changed++;
					}
				}
				else
				{
					// Concave notch: an empty tile with two adjacent overlay neighbours —
					// fill the corner between them with the matching diagonal.
					int rot = -1, fill = 0;
					if (n && e && !(x < 63 && y < 63 && has[x + 1][y + 1])) { rot = 2; fill = firstOv(oid, x, y + 1, x + 1, y); }
					else if (s && e && !(x < 63 && y > 0 && has[x + 1][y - 1])) { rot = 1; fill = firstOv(oid, x, y - 1, x + 1, y); }
					else if (s && w && !(x > 0 && y > 0 && has[x - 1][y - 1])) { rot = 0; fill = firstOv(oid, x, y - 1, x - 1, y); }
					else if (n && w && !(x > 0 && y < 63 && has[x - 1][y + 1])) { rot = 3; fill = firstOv(oid, x, y + 1, x - 1, y); }
					if (rot >= 0 && fill != 0)
					{
						region.setOverlay(plane, x, y, fill, 1, rot);
						changed++;
					}
					else if ((n ? 1 : 0) + (s ? 1 : 0) + (e ? 1 : 0) + (w ? 1 : 0) >= 3)
					{
						// Deep notch: a land tile with 3-4 overlay neighbours — a single tile
						// biting into the river. Fill it fully so the inner curve rounds off.
						int fill2 = n ? oid[x][y + 1] : s ? oid[x][y - 1] : e ? oid[x + 1][y] : oid[x - 1][y];
						if (fill2 != 0)
						{
							region.setOverlay(plane, x, y, fill2, 0, 0);
							changed++;
						}
					}
				}
			}
		}
		sceneDirty = true;
		rerender();
		status.setText(" Smoothed " + changed + " overlay edge tiles"
			+ (areaOnly ? " in the selection" : " on plane " + plane));
	}

	private static int firstOv(int[][] oid, int ax, int ay, int bx, int by)
	{
		int a = oid[ax][ay];
		return a != 0 ? a : oid[bx][by];
	}

	/**
	 * One-click bridge over the Area-fill selection. On the current (upper) plane it
	 * flattens the deck down to the bank height of the plane below and sets the OSRS
	 * "bridge" tile flag (settings bit 2) so the player walks over while the water/road
	 * below stays untouched and passable. Place a bridge object on the deck afterwards.
	 */
	private void makeBridge()
	{
		if (region == null)
		{
			return;
		}
		if (plane < 1)
		{
			JOptionPane.showMessageDialog(this,
				"Switch to plane 1 first — the bridge deck is built on the plane above the water.",
				"Make bridge", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (areaX0 < 0)
		{
			JOptionPane.showMessageDialog(this,
				"Turn on Area fill and drag a rectangle across the span (bank to bank) first.",
				"Make bridge", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		int x0 = Math.min(areaX0, areaX1), x1 = Math.max(areaX0, areaX1);
		int y0 = Math.min(areaY0, areaY1), y1 = Math.max(areaY0, areaY1);

		net.runelite.cache.region.Region r = new net.runelite.cache.region.Region(region.getRegionId());
		r.loadTerrain(region.getMap());

		// Deck level. If the Height tool's fixed field has a value, use it as the exact
		// absolute bank height (read it off the Tile-heights overlay) — this gives a dead-flat
		// deck that meets the bank even when a few tiles in the selection are slightly higher.
		// Otherwise fall back to the highest ground under the selection. (Up is negative, so
		// world Y = -height*8 and "highest" ground is the most-negative = the minimum.)
		int below = plane - 1;
		int target;
		String hf = heightField.getText().trim();
		if (!hf.isEmpty())
		{
			target = -Integer.parseInt(hf) * 8;
		}
		else
		{
			target = Integer.MAX_VALUE;
			for (int x = x0; x <= x1; x++)
			{
				for (int y = y0; y <= y1; y++)
				{
					target = Math.min(target, r.getTileHeight(below, x, y));
				}
			}
		}

		pushUndo();
		int n = 0;
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				// Byte that puts this deck tile flat at `target`:
				// deckHeight = belowHeight - byte*8  ⇒  byte = (belowHeight - target)/8.
				int b = Math.round((r.getTileHeight(below, x, y) - target) / 8f);
				b = Math.max(0, Math.min(255, b));
				region.setHeight(plane, x, y, b);
				// Set the bridge/link flag (bit 2), keep any existing collision bits.
				int s = region.getTile(plane, x, y).settings & 0xFF;
				region.setSettings(plane, x, y, s | 2);
				n++;
			}
		}
		rebuildPlaneGrid();
		sceneDirty = true;
		rerender();
		status.setText(" Bridge deck: " + n + " tiles flattened to bank level + bridge flag set. "
			+ "Now place a bridge object (Objects → \"bridge\") along it.");
	}

	/**
	 * Undo a bridge deck over the Area-fill selection: reset every tile on the current plane
	 * back to auto height and strip the OSRS "bridge" flag (settings bit 2), so a botched or
	 * left-over bridge can be cleared and redone. Does not touch placed bridge objects.
	 */
	private void clearBridge()
	{
		if (region == null)
		{
			return;
		}
		if (plane < 1)
		{
			JOptionPane.showMessageDialog(this,
				"Switch to plane 1 first — that's where the bridge deck lives.",
				"Clear bridge", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (areaX0 < 0)
		{
			JOptionPane.showMessageDialog(this,
				"Turn on Area fill and drag a rectangle across the old bridge span first.",
				"Clear bridge", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		int x0 = Math.min(areaX0, areaX1), x1 = Math.max(areaX0, areaX1);
		int y0 = Math.min(areaY0, areaY1), y1 = Math.max(areaY0, areaY1);

		pushUndo();
		int n = 0;
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				region.setHeight(plane, x, y, null); // back to auto height
				int s = region.getTile(plane, x, y).settings & 0xFF;
				region.setSettings(plane, x, y, s & ~2); // strip the bridge flag
				n++;
			}
		}
		rebuildPlaneGrid();
		sceneDirty = true;
		rerender();
		status.setText(" Cleared bridge: " + n + " tiles reset to auto height + bridge flag removed. "
			+ "Delete the bridge object separately if you want, then redo.");
	}

	/**
	 * Caches the current plane's 65x65 corner heights so the 3D grid drapes over the
	 * real terrain surface — the same surface objects are placed on, so they read as
	 * sitting on the grid.
	 */
	private void rebuildPlaneGrid()
	{
		if (region == null)
		{
			planeGridHeights = null;
			return;
		}
		net.runelite.cache.region.Region r = new net.runelite.cache.region.Region(region.getRegionId());
		r.loadTerrain(region.getMap());
		int[][] h = new int[65][65];
		for (int x = 0; x <= 64; x++)
		{
			for (int y = 0; y <= 64; y++)
			{
				h[x][y] = r.getTileHeight(plane, Math.min(x, 63), Math.min(y, 63));
			}
		}
		planeGridHeights = h;
	}

	private void updateSelCorners()
	{
		if (selX < 0 || region == null)
		{
			selCorners3D = null;
		}
		else
		{
			net.runelite.cache.region.Region r = new net.runelite.cache.region.Region(region.getRegionId());
			r.loadTerrain(region.getMap());
			int x = selX, y = selY, t = 128;
			int x1 = Math.min(x + 1, 63), y1 = Math.min(y + 1, 63);
			int h00 = r.getTileHeight(plane, x, y), h10 = r.getTileHeight(plane, x1, y);
			int h11 = r.getTileHeight(plane, x1, y1), h01 = r.getTileHeight(plane, x, y1);
			selCorners3D = new double[][]{
				{x * t, h00, y * t},
				{(x + 1) * t, h10, y * t},
				{(x + 1) * t, h11, (y + 1) * t},
				{x * t, h01, (y + 1) * t}
			};
		}
		if (show3D())
		{
			canvas3D.repaint();
		}
	}

	/** Apply a per-tile op over the brush square centred on (x,y). */
	private void forBrush(int x, int y, TileOp op)
	{
		for (int dx = -brushSize; dx <= brushSize; dx++)
		{
			for (int dy = -brushSize; dy <= brushSize; dy++)
			{
				int nx = x + dx, ny = y + dy;
				if (nx >= 0 && nx <= 63 && ny >= 0 && ny <= 63)
				{
					op.apply(nx, ny);
				}
			}
		}
	}

	/** The four terrain tools, applied to a single tile (used by brush + area fill). */
	/** Eyedropper: read a tile's terrain and load it into the tool fields. */
	private void pickTerrain(int x, int y)
	{
		if (region == null)
		{
			return;
		}
		var tile = region.getTile(plane, x, y);
		if (tile == null)
		{
			return;
		}
		int un = tile.underlayId & 0xFFFF, ov = tile.overlayId & 0xFFFF;
		int path = tile.overlayPath & 0xFF, rot = tile.overlayRotation & 3;
		underlayField.setText(String.valueOf(tileIdToDef(un)));
		overlayField.setText(String.valueOf(tileIdToDef(ov)));
		overlayPathField.setText(String.valueOf(path));
		overlayRotField.setText(String.valueOf(rot));
		settingsField.setText(String.valueOf(tile.settings & 0xFF));
		// Show the ABSOLUTE world height (matches the Tile-heights overlay and the fixed-height
		// field's meaning); blank when the tile uses auto height.
		heightField.setText(tile.height == null ? "" : String.valueOf(worldHeightUnits(plane, x, y)));

		// Switch to the tool that reproduces what you clicked, so the next paint
		// stamps that same ground. An overlay-less tile (e.g. plain grass) is an
		// UNDERLAY — painting it with the Overlay tool (overlay 0) would just erase.
		String picked;
		if (ov != 0)
		{
			setTool(Tool.OVERLAY);
			picked = "overlay " + ov + " (path " + path + " rot " + rot + ")";
		}
		else if (un != 0)
		{
			setTool(Tool.UNDERLAY);
			picked = "underlay " + un;
		}
		else
		{
			picked = "empty tile";
		}
		status.setText(" Picked " + picked + " → " + (tool == Tool.OVERLAY ? "Overlay" : "Underlay")
			+ " tool ready; click to paint it");
	}

	private boolean isTerrainTool()
	{
		return tool == Tool.UNDERLAY || tool == Tool.OVERLAY || tool == Tool.HEIGHT || tool == Tool.SETTINGS;
	}

	/** Human label for a brush radius, e.g. 0 → "0 (1×1)", 10 → "10 (21×21)". */
	private static String brushLabel(int r)
	{
		int n = 2 * r + 1;
		return r + " (" + n + "×" + n + ")";
	}

	// Tiles store overlay/underlay ids as (definition + 1), with 0 = none (matches the cache/game).
	// The palette + fields use definition ids, so convert when writing (paint) and reading (pick).
	private static int defToTileId(int def) { return def > 0 ? def + 1 : 0; }
	private static int tileIdToDef(int raw) { return raw > 0 ? raw - 1 : 0; }

	private void applyTerrainAt(int x, int y)
	{
		switch (tool)
		{
			case UNDERLAY:
				region.setUnderlay(plane, x, y, defToTileId(parse(underlayField, 0)));
				break;
			case OVERLAY:
				region.setOverlay(plane, x, y, defToTileId(parse(overlayField, 0)),
					parse(overlayPathField, 0), parse(overlayRotField, 0));
				if (paintUnderlayToo)
				{
					region.setUnderlay(plane, x, y, defToTileId(parse(underlayField, 0)));
				}
				break;
			case HEIGHT:
			{
				String h = heightField.getText().trim();
				if (!h.isEmpty())
				{
					// FIXED height: set the tile's ABSOLUTE world height to this value — the
					// same number the "Tile heights" overlay shows. OSRS heights are per-corner
					// (each tile is 4 shared vertices), so to make the tile sit FLAT at v we set
					// all four of its corners, not just the SW one (setting one corner would leave
					// the tile sloping toward its neighbours — and the overlay, being a 4-corner
					// average, would then read a blended value instead of v).
					int v = Integer.parseInt(h);
					setAbsoluteCornerHeight(plane, x, y, v);
					setAbsoluteCornerHeight(plane, x + 1, y, v);
					setAbsoluteCornerHeight(plane, x, y + 1, v);
					setAbsoluteCornerHeight(plane, x + 1, y + 1, v);
				}
				else
				{
					// SCULPT mode (blank field): click raises, right/shift lowers by step.
					int cur = effectiveStoredHeight(x, y);
					int step = heightStep;
					int nv = Math.max(0, Math.min(255, cur + (paintLower ? -step : step)));
					if (nv == 1)
					{
						nv = paintLower ? 0 : 2; // the client treats stored height 1 as 0
					}
					region.setHeight(plane, x, y, nv);
				}
				break;
			}
			case SETTINGS:
				region.setSettings(plane, x, y, parse(settingsField, 0));
				break;
			default:
		}
	}

	private void applyAreaFill()
	{
		if (region == null || areaX0 < 0)
		{
			return;
		}
		boolean terrain = tool == Tool.UNDERLAY || tool == Tool.OVERLAY
			|| tool == Tool.HEIGHT || tool == Tool.SETTINGS;
		if (!terrain && tool != Tool.DELETE_OBJECT)
		{
			status.setText(" Area fill works with a terrain tool or the Delete tool");
			return;
		}
		int minX = Math.min(areaX0, areaX1), maxX = Math.max(areaX0, areaX1);
		int minY = Math.min(areaY0, areaY1), maxY = Math.max(areaY0, areaY1);
		try
		{
			pushUndo();
			strokeHeights = null;
			if (tool == Tool.DELETE_OBJECT)
			{
				// Bulk delete: every object + placed NPC spawn in the rectangle.
				int removed = 0;
				for (int x = minX; x <= maxX; x++)
				{
					for (int y = minY; y <= maxY; y++)
					{
						if (service.removeSessionNpcSpawnAt(region.getBaseX() + x, region.getBaseY() + y, plane))
						{
							removed++;
						}
						for (Location l : region.locationsAt(plane, x, y))
						{
							region.removeLocation(l);
							removed++;
						}
					}
				}
				sceneDirty = true;
				rerender();
				status.setText(" Deleted " + removed + " objects/spawns in the selection");
				return;
			}
			for (int x = minX; x <= maxX; x++)
			{
				for (int y = minY; y <= maxY; y++)
				{
					applyTerrainAt(x, y);
				}
			}
			sceneDirty = true;
			rerender();
			status.setText(" Filled " + ((maxX - minX + 1) * (maxY - minY + 1)) + " tiles");
		}
		catch (NumberFormatException ex)
		{
			status.setText(" Invalid number in a parameter field");
		}
	}

	private void warnNoKey()
	{
		JOptionPane.showMessageDialog(this,
			"This region's location archive can't be decrypted (missing XTEA key),\n"
				+ "so objects can't be edited here.", "Objects locked", JOptionPane.WARNING_MESSAGE);
	}

	private void save()
	{
		if (region == null)
		{
			return;
		}
		try
		{
			boolean hadNewOverlays = service.hasUnsavedOverlays();
			service.saveOverlays(); // persist any new texture overlays first
			service.saveRegion(region);
			String spawnMsg = service.saveSessionSpawns();
			rerender();
			status.setText(" Saved region " + region.getRegionId() + " to cache."
				+ (hadNewOverlays ? "  + new texture overlays" : "")
				+ (spawnMsg != null ? "  Spawns: " + spawnMsg : ""));
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Save failed:\n" + ex.getMessage(),
				"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void exportRegionToml()
	{
		if (region == null)
		{
			return;
		}
		int rx = region.getRegionX(), ry = region.getRegionY();
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Export region " + region.getRegionId() + " to TOML");
		fc.setSelectedFile(new File("region.toml"));
		// Default to the server's TOML region folder if it exists.
		File tomlDir = new File(new File(service.getCacheDir().getParentFile(), "cache"),
			"toml/0_jagex/region/" + rx + "_" + ry);
		if (tomlDir.isDirectory())
		{
			fc.setCurrentDirectory(tomlDir);
		}
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File out = fc.getSelectedFile();
		try
		{
			java.nio.file.Files.writeString(out.toPath(), TomlExporter.export(region),
				java.nio.charset.StandardCharsets.UTF_8);
			status.setText(" Exported region " + region.getRegionId() + " → " + out.getName());
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(),
				"Export TOML", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Dump the current region's raw cache archives (m{x}_{y} / l{x}_{y}) to a chosen folder.
	 * Export-only — nothing is written back to the cache.
	 */
	private void dumpRegionFiles()
	{
		if (region == null)
		{
			return;
		}
		// Terrain format: this server's cache is SHORT (16-bit ids), but vanilla-format tools such as
		// RSPSi read one byte per attribute and will overrun a SHORT buffer. Let the user pick, and
		// warn if the region's ids can't survive the narrower encoding.
		String nativeName = region.mapFmt == MapCodec.Fmt.SHORT ? "short" : "byte";
		Object[] choices = {
			"Native (" + nativeName + ") — m/l names, this cache",
			"Byte — m/l names, vanilla tools",
			"Byte + archive-id names — RSPSi",
		};
		int pick = JOptionPane.showOptionDialog(this,
			"Format and naming for the dump?\n\n"
				+ "RSPSi identifies map files by archive id (e.g. 624.dat), not m/l names —\n"
				+ "given m/l names it cannot tell terrain from locations.",
			"Dump region " + region.getRegionId(),
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
		if (pick < 0)
		{
			return;
		}
		MapCodec.Fmt fmt = pick == 0 ? region.mapFmt : MapCodec.Fmt.BYTE;
		boolean byArchiveId = pick == 2;

		if (fmt == MapCodec.Fmt.BYTE)
		{
			int[] over = MapEditorService.countByteFmtOverflows(region.getMap());
			if (over[0] + over[1] > 0)
			{
				int go = JOptionPane.showConfirmDialog(this,
					"This region does not fit byte format:\n\n"
						+ "  " + over[0] + " tiles with overlay id > 255\n"
						+ "  " + over[1] + " tiles with underlay id > 174\n\n"
						+ "Those ids will be silently truncated, so the dump will load in RSPSi but\n"
						+ "show the wrong ground on those tiles. Dump anyway?",
					"Byte format overflow", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (go != JOptionPane.YES_OPTION)
				{
					return;
				}
			}
		}

		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Dump region " + region.getRegionId() + " (m/l files) to folder");
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		try
		{
			File[] out = service.dumpRegion(region, fc.getSelectedFile(), fmt, byArchiveId);
			status.setText(" Dumped " + out[0].getName() + " (" + out[0].length() + " B) + "
				+ out[1].getName() + " (" + out[1].length() + " B) → " + out[0].getParent());
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Dump failed:\n" + ex.getMessage(),
				"Dump region", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** Remembered location of tool-cache-packer once found, so we don't re-search each save. */
	private File packerExe;

	/**
	 * One-click "make the server use this edit": write the current region to the server's TOML
	 * source (data/cache/toml/0_jagex/region/{x}_{y}/region.toml) and repack the binary cache
	 * from TOML with tool-cache-packer. This keeps the TOML source and the served binary cache in
	 * sync, so the change survives future server rebuilds and the client CRCs match on next login.
	 */
	private void saveToServer()
	{
		if (region == null)
		{
			return;
		}
		File cacheDir = service.getCacheDir();
		File tomlRoot = new File(cacheDir, "toml");
		if (!tomlRoot.isDirectory())
		{
			JOptionPane.showMessageDialog(this,
				"No 'toml' folder found next to the cache:\n" + tomlRoot
				+ "\n\nThis one-click save is for the Reason server layout (data/cache/toml/…).\n"
				+ "Use File → Export region to TOML instead.",
				"Save to server", JOptionPane.WARNING_MESSAGE);
			return;
		}

		File packer = locatePacker(cacheDir);
		if (packer == null)
		{
			return; // user cancelled locating it
		}

		int rx = region.getRegionX(), ry = region.getRegionY();
		File layer = chooseTomlLayer(tomlRoot, rx, ry, packer);
		if (layer == null)
		{
			return; // user cancelled
		}
		File regionDir = new File(layer, "region/" + rx + "_" + ry);
		File tomlFile = new File(regionDir, "region.toml");

		// 1. Write the region TOML (the server's source of truth) into the chosen layer.
		try
		{
			regionDir.mkdirs();
			java.nio.file.Files.writeString(tomlFile.toPath(), TomlExporter.export(region),
				java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Failed to write region.toml:\n" + ex.getMessage(),
				"Save to server", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// 2. Repack the binary cache from TOML in the background.
		runPacker(packer, tomlRoot, cacheDir);
	}

	/**
	 * Let the user choose which TOML layer to write the region into. The packer merges layers by
	 * numeric-prefix priority (0_jagex = base, 1_* override it), so an edit must go into whichever
	 * layer currently "wins" for that region or a higher layer would mask it. Returns null if the
	 * user cancels.
	 */
	private File chooseTomlLayer(File tomlRoot, int rx, int ry, File packer)
	{
		final String rel = "region/" + rx + "_" + ry + "/region.toml";
		File[] all = tomlRoot.listFiles(File::isDirectory);
		if (all == null || all.length == 0)
		{
			return new File(tomlRoot, "0_jagex"); // nothing to choose from
		}
		// Highest priority first (bigger leading number wins), then by name.
		java.util.Arrays.sort(all, (a, b) ->
		{
			int pa = layerPriority(a.getName()), pb = layerPriority(b.getName());
			return pa != pb ? Integer.compare(pb, pa) : a.getName().compareToIgnoreCase(b.getName());
		});
		// Only layers that hold regions are relevant (they have a region/ folder), plus 0_jagex.
		java.util.List<File> layers = new java.util.ArrayList<>();
		for (File d : all)
		{
			if (new File(d, "region").isDirectory() || d.getName().endsWith("_jagex"))
			{
				layers.add(d);
			}
		}
		if (layers.isEmpty())
		{
			layers.addAll(java.util.Arrays.asList(all));
		}
		// The layer the game currently loads = highest-priority one that already has this region.
		File winner = null;
		for (File d : layers)
		{
			if (new File(d, rel).isFile())
			{
				winner = d;
				break;
			}
		}

		javax.swing.JComboBox<File> combo = new javax.swing.JComboBox<>(layers.toArray(new File[0]));
		combo.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
				int index, boolean sel, boolean focus)
			{
				super.getListCellRendererComponent(list, value, index, sel, focus);
				File d = (File) value;
				String tag = d.getName().endsWith("_jagex") ? "  (base Jagex)" : "  (override)";
				if (new File(d, rel).isFile())
				{
					tag += "  [has this region]";
				}
				setText(d.getName() + tag);
				return this;
			}
		});
		if (winner != null)
		{
			combo.setSelectedItem(winner);
		}

		String note = winner != null
			? "The game currently loads " + rx + "_" + ry + " from <b>" + winner.getName()
			  + "</b>. Save into that layer to change what appears in-game."
			: rx + "_" + ry + " isn't in any layer yet — choose where to create it "
			  + "(<b>1_patches</b> is the usual place for custom regions).";
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.add(new JLabel("<html><body style='width:380px'>"
			+ "Which TOML layer should this region be saved into?<br>"
			+ "Higher layers (<b>1_*</b>) override <b>0_jagex</b>.<br><br>"
			+ note + "<br><br>Then the cache is repacked with <b>" + packer.getName()
			+ "</b> — restart the server &amp; client afterwards.</body></html>"), BorderLayout.NORTH);
		panel.add(combo, BorderLayout.SOUTH);

		int ok = JOptionPane.showConfirmDialog(this, panel, "Save to server — choose layer",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		return ok == JOptionPane.OK_OPTION ? (File) combo.getSelectedItem() : null;
	}

	/** Leading integer of a layer folder name ("1_patches" -> 1); higher = overrides lower. */
	private static int layerPriority(String name)
	{
		int us = name.indexOf('_');
		if (us > 0)
		{
			try
			{
				return Integer.parseInt(name.substring(0, us));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return 0;
	}

	/** Find tool-cache-packer in the usual spots, else ask the user to point at it (remembered). */
	private File locatePacker(File cacheDir)
	{
		if (packerExe != null && packerExe.isFile())
		{
			return packerExe;
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		File serverRoot = cacheDir.getParentFile() != null ? cacheDir.getParentFile().getParentFile() : null;
		java.util.List<File> candidates = new java.util.ArrayList<>();
		// On Windows, only .exe candidates are valid executables (a bare Linux binary gives
		// CreateProcess error=193). Prefer the .exe next to the server, then the Desktop one.
		if (serverRoot != null)
		{
			candidates.add(new File(serverRoot, ".dev/tool-cache-packer.exe"));
		}
		String home = System.getProperty("user.home");
		if (home != null)
		{
			candidates.add(new File(home, "Desktop/tool-cache-packer.exe"));
		}
		if (!windows)
		{
			// Non-Windows: accept the extensionless binary.
			if (serverRoot != null)
			{
				candidates.add(new File(serverRoot, ".dev/tool-cache-packer"));
			}
			if (home != null)
			{
				candidates.add(new File(home, "Desktop/tool-cache-packer"));
			}
		}
		for (File c : candidates)
		{
			if (c.isFile())
			{
				packerExe = c;
				return c;
			}
		}
		JOptionPane.showMessageDialog(this,
			"Couldn't find tool-cache-packer automatically.\nPlease locate it (tool-cache-packer.exe).",
			"Save to server", JOptionPane.INFORMATION_MESSAGE);
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Locate tool-cache-packer.exe");
		if (serverRoot != null && new File(serverRoot, ".dev").isDirectory())
		{
			fc.setCurrentDirectory(new File(serverRoot, ".dev"));
		}
		if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile().isFile())
		{
			packerExe = fc.getSelectedFile();
			return packerExe;
		}
		return null;
	}

	/** Run the packer (--in tomlRoot --out outDir), streaming its output into a modal dialog. */
	private void runPacker(final File packer, final File tomlRoot, final File outDir)
	{
		final JDialog dlg = new JDialog(this, "Repacking cache…", true);
		final javax.swing.JTextArea log = new javax.swing.JTextArea(18, 70);
		log.setEditable(false);
		log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		final javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
		bar.setIndeterminate(true);
		final JButton close = new JButton("Please wait…");
		close.setEnabled(false);
		close.addActionListener(a -> dlg.dispose());
		JPanel south = new JPanel(new BorderLayout(8, 0));
		south.add(bar, BorderLayout.CENTER);
		south.add(close, BorderLayout.EAST);
		dlg.getContentPane().add(new JScrollPane(log), BorderLayout.CENTER);
		dlg.getContentPane().add(south, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(this);

		final int savedRegionId = region.getRegionId();
		javax.swing.SwingWorker<Integer, String> worker = new javax.swing.SwingWorker<Integer, String>()
		{
			@Override
			protected Integer doInBackground() throws Exception
			{
				ProcessBuilder pb = new ProcessBuilder(
					packer.getAbsolutePath(),
					"--in", tomlRoot.getAbsolutePath(),
					"--out", outDir.getAbsolutePath());
				pb.redirectErrorStream(true);
				pb.environment().put("RUST_LOG", "info");
				publish("> " + String.join(" ", pb.command()));
				publish("");
				Process p = pb.start();
				try (java.io.BufferedReader r = new java.io.BufferedReader(
					new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)))
				{
					String line;
					while ((line = r.readLine()) != null)
					{
						publish(line);
					}
				}
				return p.waitFor();
			}

			@Override
			protected void process(java.util.List<String> chunks)
			{
				for (String s : chunks)
				{
					log.append(s + "\n");
				}
				log.setCaretPosition(log.getDocument().getLength());
			}

			@Override
			protected void done()
			{
				int code;
				try
				{
					code = get();
				}
				catch (Exception ex)
				{
					log.append("\nFAILED to run packer: " + ex.getMessage() + "\n");
					code = -1;
				}
				bar.setIndeterminate(false);
				bar.setValue(100);
				if (code == 0)
				{
					log.append("\nDone. Cache repacked from TOML.\n"
						+ "Now fully restart the game server, then launch a fresh client.\n");
					status.setText(" Saved region " + savedRegionId + " to server + repacked cache.");
					close.setText("Done");
				}
				else
				{
					log.append("\nPacker exited with code " + code + " — cache was NOT updated.\n");
					close.setText("Close");
				}
				close.setEnabled(true);
			}
		};
		worker.execute();
		dlg.setVisible(true); // modal; the background worker keeps updating the EDT via publish/done
	}

	private void reload()
	{
		if (region == null)
		{
			return;
		}
		if (region.isDirty() && JOptionPane.showConfirmDialog(this,
			"Discard unsaved changes and reload?", "Reload", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}
		loadRegion(region.getRegionId());
	}

	// ---- dialogs -------------------------------------------------------

	private void openCache()
	{
		if (region != null && region.isDirty() && JOptionPane.showConfirmDialog(this,
			"The current region has unsaved changes. Discard them and open another cache?",
			"Open Cache", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}
		JFileChooser fc = new JFileChooser();
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fc.setDialogTitle("Select cache folder (contains main_file_cache.dat2)");
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File dir = fc.getSelectedFile();
		if (!new File(dir, "main_file_cache.dat2").exists()
			&& JOptionPane.showConfirmDialog(this,
				"This folder has no main_file_cache.dat2 — open it anyway?",
				"Open Cache", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}

		// Find keys next to the cache, else let the user pick (Cancel = no keys).
		File xteas = MapEditor.findXteas(dir);
		if (xteas == null)
		{
			File start = dir.getParentFile() != null ? dir.getParentFile() : dir;
			JFileChooser kf = new JFileChooser(start);
			kf.setFileSelectionMode(JFileChooser.FILES_ONLY);
			kf.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("XTEA keys (*.json)", "json"));
			kf.setDialogTitle("Select XTEA keys (region_keys.json / xteas.json) — Cancel to continue without");
			xteas = kf.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? kf.getSelectedFile() : null;
		}
		loadCacheInNewWindow(dir, xteas);
	}

	/**
	 * Opens a cache in a fresh editor window: shows a modal loading dialog while the
	 * (heavy) cache load runs on a background thread, then swaps in the new window and
	 * closes this one. Rebuilding the whole frame is how the palettes/region reset
	 * cleanly for the new cache.
	 */
	private void loadCacheInNewWindow(File dir, File xteas)
	{
		JDialog loading = new JDialog(this, "Opening cache…", true);
		JPanel p = new JPanel(new java.awt.BorderLayout(12, 12));
		p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
		p.add(new JLabel("Loading cache:  " + dir.getName()), java.awt.BorderLayout.NORTH);
		javax.swing.JProgressBar prog = new javax.swing.JProgressBar();
		prog.setIndeterminate(true);
		p.add(prog, java.awt.BorderLayout.CENTER);
		p.add(new JLabel("Reading configs, models, spawns…"), java.awt.BorderLayout.SOUTH);
		loading.setContentPane(p);
		loading.pack();
		loading.setLocationRelativeTo(this);
		loading.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);

		final java.awt.Rectangle bounds = getBounds();
		final int state = getExtendedState();
		javax.swing.SwingWorker<MapEditorService, Void> worker =
			new javax.swing.SwingWorker<MapEditorService, Void>()
		{
			@Override protected MapEditorService doInBackground() throws Exception
			{
				MapEditorService s = new MapEditorService(dir, new JsonXteaKeyProvider(xteas));
				s.open();
				return s;
			}
			@Override protected void done()
			{
				loading.dispose();
				MapEditorService s;
				try
				{
					s = get();
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(MapEditorFrame.this,
						"Failed to open cache:\n" + cause.getMessage(), "Open Cache", JOptionPane.ERROR_MESSAGE);
					return;
				}
				int region = 12850;
				if (!s.regionExists(region))
				{
					java.util.List<Integer> rs = s.listRegions();
					if (!rs.isEmpty())
					{
						region = rs.get(0);
					}
				}
				MapEditorFrame f = new MapEditorFrame(s, region);
				f.setBounds(bounds);
				f.setExtendedState(state);
				f.setVisible(true);
				try
				{
					service.close();
				}
				catch (Exception ignored)
				{
				}
				dispose();
			}
		};
		worker.execute();
		loading.setVisible(true); // modal; returns when done() disposes it
	}

	private void goToDialog()
	{
		openWorldMap();
	}

	/** A clickable world map of all regions (like a minimap) to jump between them. */
	private void openWorldMap()
	{
		java.util.List<Integer> regions = service.listRegions();
		if (regions.isEmpty())
		{
			typeRegionDialog();
			return;
		}
		final java.util.Set<Integer> exist = new java.util.HashSet<>(regions);
		int minX = 255, minY = 255, maxX = 0, maxY = 0;
		for (int r : regions)
		{
			int rx = r >> 8, ry = r & 0xFF;
			minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
			minY = Math.min(minY, ry); maxY = Math.max(maxY, ry);
		}
		final int mnX = minX, mnY = minY, cols = maxX - minX + 1, rows = maxY - minY + 1;
		final int[] cell = {22};   // mutable so the mouse wheel can zoom the map
		final java.util.Map<Integer, BufferedImage> thumbs = new java.util.HashMap<>();
		final int[] hover = {-1, -1};

		JDialog dialog = new JDialog(this, "World map — click a region to open (wheel = zoom, middle-drag = pan)", true);
		JLabel info = new JLabel(" hover a region · scroll wheel to zoom");

		JComponent grid = new JComponent()
		{
			@Override public Dimension getPreferredSize()
			{
				return new Dimension(cols * cell[0], rows * cell[0]);
			}

			@Override protected void paintComponent(Graphics g)
			{
				int c = cell[0];
				g.setColor(new Color(0x14161A));
				g.fillRect(0, 0, getWidth(), getHeight());
				java.awt.Rectangle clip = g.getClipBounds();
				for (int rx = mnX; rx < mnX + cols; rx++)
				{
					for (int ry = mnY; ry < mnY + rows; ry++)
					{
						int id = (rx << 8) | ry;
						int px = (rx - mnX) * c;
						int py = (rows - 1 - (ry - mnY)) * c; // north up
						if (!clip.intersects(px, py, c, c))
						{
							continue;
						}
						if (exist.contains(id))
						{
							BufferedImage th = thumbs.get(id);
							if (th == null && !thumbs.containsKey(id))
							{
								th = worldThumb(id);
								thumbs.put(id, th);
							}
							if (th != null)
							{
								g.drawImage(th, px, py, c, c, null);
							}
							else
							{
								g.setColor(new Color(0x2d5a27));
								g.fillRect(px, py, c, c);
							}
						}
						g.setColor(new Color(0, 0, 0, 60));
						g.drawRect(px, py, c, c);
						if (region != null && id == region.getRegionId())
						{
							g.setColor(Color.YELLOW);
							((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(2f));
							g.drawRect(px + 1, py + 1, c - 2, c - 2);
						}
						if (rx == hover[0] && ry == hover[1])
						{
							g.setColor(new Color(255, 255, 255, 120));
							g.fillRect(px, py, c, c);
						}
					}
				}
			}
		};
		grid.setPreferredSize(new Dimension(cols * cell[0], rows * cell[0]));

		JScrollPane sp = new JScrollPane(grid);

		MouseAdapter ma = new MouseAdapter()
		{
			private java.awt.Point panStart;      // screen coords when a middle-drag pan began
			private java.awt.Point panStartView;  // viewport position at pan start

			private int[] at(MouseEvent e)
			{
				int rx = mnX + e.getX() / cell[0];
				int ry = mnY + (rows - 1 - e.getY() / cell[0]);
				if (rx < mnX || rx >= mnX + cols || ry < mnY || ry >= mnY + rows)
				{
					return null;
				}
				return new int[]{rx, ry};
			}

			@Override public void mouseMoved(MouseEvent e)
			{
				int[] t = at(e);
				if (t != null)
				{
					hover[0] = t[0]; hover[1] = t[1];
					int id = (t[0] << 8) | t[1];
					info.setText(" region " + id + "  (" + t[0] + "," + t[1] + ")  world "
						+ (t[0] << 6) + "," + (t[1] << 6) + (exist.contains(id) ? "" : "  — empty"));
					grid.repaint();
				}
			}

			// Middle-drag pans the world map (same as the 2D map).
			@Override public void mouseDragged(MouseEvent e)
			{
				if (panStart == null)
				{
					return;
				}
				java.awt.Point now = e.getLocationOnScreen();
				int dx = panStart.x - now.x, dy = panStart.y - now.y;
				javax.swing.JViewport vp = sp.getViewport();
				Dimension pref = grid.getPreferredSize();
				int nx = Math.max(0, Math.min(panStartView.x + dx, Math.max(0, pref.width - vp.getWidth())));
				int ny = Math.max(0, Math.min(panStartView.y + dy, Math.max(0, pref.height - vp.getHeight())));
				vp.setViewPosition(new java.awt.Point(nx, ny));
			}

			@Override public void mouseReleased(MouseEvent e)
			{
				if (panStart != null)
				{
					panStart = null;
					grid.setCursor(java.awt.Cursor.getDefaultCursor());
				}
			}

			@Override public void mousePressed(MouseEvent e)
			{
				// Middle button = grab-and-pan (like the 2D map); left button opens a region.
				if (javax.swing.SwingUtilities.isMiddleMouseButton(e))
				{
					panStart = e.getLocationOnScreen();
					panStartView = sp.getViewport().getViewPosition();
					grid.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
					return;
				}
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				int[] t = at(e);
				if (t == null)
				{
					return;
				}
				int id = (t[0] << 8) | t[1];
				if (exist.contains(id))
				{
					dialog.dispose();
					loadRegion(id);
				}
				else if (JOptionPane.showConfirmDialog(dialog,
					"Region " + id + " (" + t[0] + "," + t[1] + ") is empty. Create it here?",
					"Add region", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
				{
					dialog.dispose();
					createRegion(t[0], t[1]);
				}
			}

			@Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e)
			{
				// Cursor-anchored zoom: keep the region under the pointer fixed.
				javax.swing.JViewport vp = sp.getViewport();
				java.awt.Point ov = vp.getViewPosition();
				int c0 = cell[0];
				int nc = Math.max(6, Math.min(80, c0 + (e.getWheelRotation() < 0 ? 4 : -4)));
				if (nc == c0)
				{
					return;
				}
				double fx = e.getX() / (double) c0, fy = e.getY() / (double) c0;
				int screenX = e.getX() - ov.x, screenY = e.getY() - ov.y;
				cell[0] = nc;
				int w = cols * nc, h = rows * nc;
				grid.setPreferredSize(new Dimension(w, h));
				grid.setSize(w, h);
				int nx = (int) (fx * nc - screenX), ny = (int) (fy * nc - screenY);
				nx = Math.max(0, Math.min(nx, Math.max(0, w - vp.getWidth())));
				ny = Math.max(0, Math.min(ny, Math.max(0, h - vp.getHeight())));
				vp.setViewPosition(new java.awt.Point(nx, ny));
				grid.revalidate();
				grid.repaint();
			}
		};
		grid.addMouseListener(ma);
		grid.addMouseMotionListener(ma);
		grid.addMouseWheelListener(ma);

		JButton typeBtn = new JButton("Type id / coords…");
		typeBtn.addActionListener(e -> { dialog.dispose(); typeRegionDialog(); });

		dialog.setLayout(new BorderLayout());
		sp.getVerticalScrollBar().setUnitIncrement(cell[0]);
		sp.getHorizontalScrollBar().setUnitIncrement(cell[0]);
		dialog.add(sp, BorderLayout.CENTER);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(info, BorderLayout.CENTER);
		bottom.add(typeBtn, BorderLayout.EAST);
		dialog.add(bottom, BorderLayout.SOUTH);
		dialog.setSize(Math.min(1100, cols * cell[0] + 60), Math.min(800, rows * cell[0] + 90));
		dialog.setLocationRelativeTo(this);
		// centre the scroll on the current region
		if (region != null)
		{
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				int px = (region.getRegionX() - mnX) * cell[0];
				int py = (rows - 1 - (region.getRegionY() - mnY)) * cell[0];
				grid.scrollRectToVisible(new java.awt.Rectangle(px - 200, py - 150, 400, 300));
			});
		}
		dialog.setVisible(true);
	}

	/** Small terrain thumbnail for the world map (null if it won't load). */
	private BufferedImage worldThumb(int regionId)
	{
		try
		{
			RegionModel rm = service.loadRegion(regionId);
			MapRenderer.Options o = new MapRenderer.Options();
			o.showObjects = false;
			o.showGrid = false;
			return renderer.render(rm, 0, 1, o); // 1px/tile = 64x64
		}
		catch (Throwable t)
		{
			return null;
		}
	}

	private void typeRegionDialog()
	{
		String in = JOptionPane.showInputDialog(this,
			"Enter region id, or 'x,y' region coords, or 'wX,Y' world coords:",
			region != null ? String.valueOf(region.getRegionId()) : "12850");
		if (in == null || in.trim().isEmpty())
		{
			return;
		}
		in = in.trim();
		try
		{
			int regionId;
			if (in.startsWith("w"))
			{
				String[] p = in.substring(1).split("[, ]+");
				regionId = ((Integer.parseInt(p[0].trim()) >> 6) << 8) | (Integer.parseInt(p[1].trim()) >> 6);
			}
			else if (in.contains(","))
			{
				String[] p = in.split("[, ]+");
				regionId = (Integer.parseInt(p[0].trim()) << 8) | Integer.parseInt(p[1].trim());
			}
			else
			{
				regionId = Integer.parseInt(in);
			}
			loadRegion(regionId);
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Could not parse: " + in, "Go To", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void addRegionDialog()
	{
		int id = service.nextFreeRegionId();
		if (id < 0)
		{
			JOptionPane.showMessageDialog(this, "No free region ids left.", "Add Region", JOptionPane.ERROR_MESSAGE);
			return;
		}
		int x = id >> 8, y = id & 0xFF;
		int choice = JOptionPane.showConfirmDialog(this,
			"Create the next free region " + id + " (" + x + "," + y + ")?\n\n"
				+ "Yes = create it now.  No = pick a spot on the world map instead.",
			"Add Region", JOptionPane.YES_NO_CANCEL_OPTION);
		if (choice == JOptionPane.YES_OPTION)
		{
			createRegion(x, y);
		}
		else if (choice == JOptionPane.NO_OPTION)
		{
			openWorldMap(); // click an empty cell to create there
		}
	}

	private void createRegion(int x, int y)
	{
		try
		{
			RegionModel created = service.addRegion(x, y);
			loadRegion(created.getRegionId());
			status.setText(" Created region " + created.getRegionId() + " (" + x + "," + y
				+ "). XTEA key 0,0,0,0 — ensure your server serves it.");
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(this, "Failed: " + ex.getMessage(), "Add Region", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void findObjectDialog()
	{
		if (service.getObjectDefs() == null)
		{
			JOptionPane.showMessageDialog(this,
				"Object definitions couldn't be read from this cache, so search by\n"
					+ "name is unavailable. You can still type an object id directly.",
				"Find object", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		JDialog dialog = new JDialog(this, "Find object", true);
		dialog.setLayout(new BorderLayout(4, 4));
		JTextField search = new JTextField();
		DefaultListModel<String> model = new DefaultListModel<>();
		JList<String> list = new JList<>(model);

		Runnable filter = () ->
		{
			String q = search.getText().trim().toLowerCase();
			model.clear();
			int shown = 0;
			for (ObjectDefinition def : service.getObjectDefs().getObjects())
			{
				String raw = def.getName();
				boolean nameless = raw == null || raw.equalsIgnoreCase("null");
				String name = nameless ? "" : raw;
				// Empty query: list only named objects (keeps the list usable). When searching,
				// match by name OR id so nameless custom objects (e.g. 46421) are findable by id.
				boolean match = q.isEmpty()
					? !nameless
					: (name.toLowerCase().contains(q) || String.valueOf(def.getId()).contains(q));
				if (match)
				{
					model.addElement(def.getId() + " — " + (nameless ? "(no name)" : name));
					if (++shown >= 500)
					{
						break;
					}
				}
			}
		};

		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { filter.run(); }
		});

		JButton pick = new JButton("Use selected");
		pick.addActionListener(e ->
		{
			String v = list.getSelectedValue();
			if (v != null)
			{
				objectIdField.setText(v.substring(0, v.indexOf(' ')));
				dialog.dispose();
			}
		});

		filter.run();
		dialog.add(search, BorderLayout.NORTH);
		dialog.add(new JScrollPane(list), BorderLayout.CENTER);
		dialog.add(pick, BorderLayout.SOUTH);
		dialog.setSize(360, 480);
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private int parse(JTextField field, int def)
	{
		try
		{
			return Integer.parseInt(field.getText().trim());
		}
		catch (NumberFormatException ex)
		{
			return def;
		}
	}

	// ---- canvas --------------------------------------------------------

	private class MapCanvas extends JComponent
	{
		private final boolean is3D;

		MapCanvas(boolean is3D)
		{
			this.is3D = is3D;
			setPreferredSize(new Dimension(64 * tileSize, 64 * tileSize));
			setFocusable(true);
			MouseAdapter ma = new MouseAdapter()
			{
				@Override public void mousePressed(MouseEvent e)
				{
					// Armed eyedropper (from a palette tab's "Pick from map" button).
					if (!is3D && pendingPick != 0 && e.getButton() == MouseEvent.BUTTON1)
					{
						int[] t = tileAt(e);
						if (t != null) { pickLayer(t[0], t[1]); }
						return;
					}
					if (is3D)
					{
						requestFocusInWindow();
						dragged3D = false;
						orbiting = e.getButton() == MouseEvent.BUTTON2; // middle button rotates
						lastDragX = e.getX();
						lastDragY = e.getY();
						// ALT + left-press on an object (Select tool) grabs it for a drag-move.
						// A plain click never moves anything — it only selects (via leftClick3D).
						drag3DLoc = null;
						drag3DMoved = false;
						lastMoveTile = null;
						if (e.getButton() == MouseEvent.BUTTON1 && e.isAltDown()
							&& tool == Tool.SELECT && region != null)
						{
							Location loc = pickObjectAt(e.getX(), e.getY());
							if (loc != null)
							{
								selectLoc(loc);
								drag3DLoc = loc;
								status.setText(" Alt-drag object " + loc.getId() + " to move it");
							}
						}
					}
					else if (e.getButton() == MouseEvent.BUTTON2) { startPan2D(e); }
					else if (e.getButton() == MouseEvent.BUTTON3) { rightClick2D(e); }
					else if (areaMode) { startArea(e); }
					else if (tool == Tool.SELECT) { select2D(e); }
					else { beginStroke(e); }
				}
				@Override public void mouseDragged(MouseEvent e)
				{
					if (is3D)
					{
						if (orbiting) { dragged3D = true; orbit(e); }
						else if (drag3DLoc != null) { dragged3D = true; dragMove3D(e); }
					}
					else if (panning2D) { doPan2D(e); }
					else if (drag2DLoc != null || drag2DNpc != null) { dragMove2D(e); }
					else if (marqueeSelecting) { updateMarquee(e); }
					else if (areaMode) { updateArea(e); }
					else if (tool != Tool.SELECT) { dragStroke(e); }
				}
				@Override public void mouseReleased(MouseEvent e)
				{
					if (drag3DLoc != null) { endDrag3D(); }
					else if (is3D && dragged3D) { render3DFull(); }
					else if (!is3D && areaMode && areaDragging) { areaDragging = false; applyAreaFill(); }
					if (marqueeSelecting) { finishMarquee(e); }
					if (drag2DLoc != null || drag2DNpc != null) { endDrag2D(); }
					if (panning2D) { panning2D = false; setCursor(java.awt.Cursor.getDefaultCursor()); }
					orbiting = false;
				}
				@Override public void mouseClicked(MouseEvent e)
				{
					if (!is3D || region == null) { return; }
					if (pendingPick != 0 && e.getButton() == MouseEvent.BUTTON1)
					{
						int[] t = pickTile(e.getX(), e.getY());
						if (t != null) { pickLayer(t[0], t[1]); }
						return;
					}
					if (e.getButton() == MouseEvent.BUTTON3) { rightClick3D(e); }
					else if (e.getButton() == MouseEvent.BUTTON1) { leftClick3D(e); }
				}
				@Override public void mouseMoved(MouseEvent e) { if (!is3D) { hover(e); } }
			};
			addMouseListener(ma);
			addMouseMotionListener(ma);
			addMouseWheelListener(e ->
			{
				if (is3D)
				{
					camera.zoom *= Math.pow(1.1, -e.getPreciseWheelRotation());
					camera.zoom = Math.max(0.15, Math.min(30.0, camera.zoom));
					render3DFull();
				}
				else
				{
					zoom2D(e);
				}
			});
			addKeyListener(new java.awt.event.KeyAdapter()
			{
				@Override public void keyPressed(java.awt.event.KeyEvent e)
				{
					if (is3D)
					{
						// N snaps the camera to face north, re-aligning the 3D view with the
						// 2D map (which is always north-up). Handy after middle-drag rotating.
						if (e.getKeyCode() == java.awt.event.KeyEvent.VK_N)
						{
							camera.yaw = 0;
							render3DFast();
							status.setText(" Camera facing north (aligned with 2D)");
							return;
						}
						// R / X rotate 90° in the 3D view too: the selected object/NPC if one is
						// picked, otherwise the armed placement rotation.
						if (e.getKeyCode() == java.awt.event.KeyEvent.VK_R
							|| e.getKeyCode() == java.awt.event.KeyEvent.VK_X)
						{
							if (selectedLoc != null) { rotateLocation(selectedLoc); return; }
							if (selectedNpc != null) { rotateSelectedNpc(); return; }
							if (tool == Tool.PLACE_OBJECT) { rotatePlacement(); return; }
						}
						// Only plain keys drive the fly camera. A modifier combo (e.g. Ctrl+Alt+S
						// to save) must NOT register its letter as a held movement key — the
						// key-release goes to the save dialog, not here, so it'd stick.
						if (!e.isControlDown() && !e.isAltDown() && !e.isMetaDown())
						{
							int kc = e.getKeyCode();
							keysDown.add(kc);
							if (!moveTimer.isRunning()) { moveTimer.start(); }
							// Stop arrow keys from scrolling/focus-traversing instead of rotating.
							if (kc == java.awt.event.KeyEvent.VK_LEFT || kc == java.awt.event.KeyEvent.VK_RIGHT
								|| kc == java.awt.event.KeyEvent.VK_UP || kc == java.awt.event.KeyEvent.VK_DOWN)
							{
								e.consume();
							}
						}
					}
				}
				@Override public void keyReleased(java.awt.event.KeyEvent e)
				{
					keysDown.remove(e.getKeyCode());
				}
			});
			// Safety net: if focus leaves the 3D canvas (a dialog opens, a menu action fires),
			// forget any held keys so the camera doesn't keep drifting.
			addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override public void focusLost(java.awt.event.FocusEvent e)
				{
					keysDown.clear();
				}
			});
		}

		/** Mouse-wheel zoom for the 2D map, anchored on the tile under the cursor. */
		private void zoom2D(java.awt.event.MouseWheelEvent e)
		{
			int oldTs = tileSize;
			// Larger step when zoomed in far so it doesn't take dozens of ticks to reach 100.
			int stepPx = Math.max(2, tileSize / 12);
			int ns = Math.max(3, Math.min(100, tileSize + (e.getWheelRotation() < 0 ? stepPx : -stepPx)));
			if (ns == oldTs)
			{
				return;
			}
			javax.swing.JViewport vp = canvasScroll.getViewport();
			java.awt.Point oldView = vp.getViewPosition();

			// e.getX()/getY() are in full-canvas coords (they already include the scroll
			// offset). Keep the tile under the cursor pinned to the same on-screen spot.
			int cursorScreenX = e.getX() - oldView.x;
			int cursorScreenY = e.getY() - oldView.y;
			double worldX = (e.getX() - offX()) / (double) oldTs; // tile under cursor
			double worldY = (e.getY() - offY()) / (double) oldTs;

			tileSize = ns;
			manual2DZoom = true;
			update2DCanvasSize();
			int w = canvas2D.getPreferredSize().width;
			int h = canvas2D.getPreferredSize().height;
			canvas2D.setSize(w, h); // apply new size now so setViewPosition clamps correctly
			render2D();

			int nox = Math.max(0, (w - 64 * ns) / 2);
			int noy = Math.max(0, (h - 64 * ns) / 2);
			int nx = (int) Math.round(nox + worldX * ns - cursorScreenX);
			int ny = (int) Math.round(noy + worldY * ns - cursorScreenY);
			nx = Math.max(0, Math.min(nx, Math.max(0, w - vp.getWidth())));
			ny = Math.max(0, Math.min(ny, Math.max(0, h - vp.getHeight())));
			vp.setViewPosition(new java.awt.Point(nx, ny));
		}

		private void orbit(MouseEvent e)
		{
			camera.yaw += (e.getX() - lastDragX) * 0.012;
			camera.pitch += (e.getY() - lastDragY) * 0.012;
			camera.pitch = Math.max(0.15, Math.min(1.55, camera.pitch));
			lastDragX = e.getX();
			lastDragY = e.getY();
			if (scene3D != null)
			{
				render3DFast();
			}
		}

		private int offX() { return Math.max(0, (getWidth() - image2DSize()) / 2) + neighbor2DStrip(); }
		private int offY() { return Math.max(0, (getHeight() - image2DSize()) / 2) + neighbor2DStrip(); }

		private int[] tileAt(MouseEvent e)
		{
			int x = (e.getX() - offX()) / tileSize;
			int y = 63 - (e.getY() - offY()) / tileSize;
			if (x < 0 || x > 63 || y < 0 || y > 63)
			{
				return null;
			}
			return new int[]{x, y};
		}

		/** First tile of a paint stroke: snapshot for undo, capture modifiers. */
		private void beginStroke(MouseEvent e)
		{
			int[] t = tileAt(e);
			if (t == null)
			{
				return;
			}
			// Paste-tiles mode: left-click stamps the copied patch here.
			if (pasteTilesMode && tileStamp != null && javax.swing.SwingUtilities.isLeftMouseButton(e))
			{
				stampTilesAt(t[0], t[1]);
				return;
			}
			// Alt+click = eyedropper: sample the tile's terrain into the fields.
			if (e.isAltDown() && isTerrainTool())
			{
				pickTerrain(t[0], t[1]);
				return;
			}
			if (tool != Tool.SELECT)
			{
				pushUndo();
			}
			paintLower = javax.swing.SwingUtilities.isRightMouseButton(e) || e.isShiftDown();
			paintAbsolute = e.isControlDown();
			applyTool(t[0], t[1]);
		}

		private void dragStroke(MouseEvent e)
		{
			int[] t = tileAt(e);
			if (t == null)
			{
				return;
			}
			if (e.isAltDown() && isTerrainTool())
			{
				pickTerrain(t[0], t[1]);
				return;
			}
			paintLower = javax.swing.SwingUtilities.isRightMouseButton(e) || e.isShiftDown();
			paintAbsolute = e.isControlDown();
			applyTool(t[0], t[1]);
		}

		private void startArea(MouseEvent e)
		{
			int[] t = tileAt(e);
			if (t != null)
			{
				areaX0 = areaX1 = t[0];
				areaY0 = areaY1 = t[1];
				areaDragging = true;
				repaint();
			}
		}

		private void updateArea(MouseEvent e)
		{
			int[] t = tileAt(e);
			if (t != null && areaDragging)
			{
				areaX1 = t[0];
				areaY1 = t[1];
				repaint();
			}
		}

		private void hover(MouseEvent e)
		{
			int[] t = tileAt(e);
			if (t != null && region != null)
			{
				boolean moved = t[0] != hoverX || t[1] != hoverY;
				hoverX = t[0]; hoverY = t[1];
				status.setText(" hover local(" + t[0] + "," + t[1] + ") world("
					+ (region.getBaseX() + t[0]) + "," + (region.getBaseY() + t[1]) + ")"
					+ (region.isDirty() ? "   *unsaved*" : ""));
				// Repaint so the brush-footprint / stamp-ghost preview follows the cursor.
				if (moved && !is3D && ((isTerrainTool() && brushSize > 0) || (pasteTilesMode && tileStamp != null)))
				{
					repaint();
				}
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			BufferedImage img = is3D ? image3D : image2D;
			int ox = is3D ? 0 : offX();
			int oy = is3D ? 0 : offY();
			if (img != null)
			{
				if (is3D)
				{
					// full or half-res 3D image scaled to fill the canvas
					g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
				}
				else
				{
					// ox/oy point to tile (0,63) of the main region; the image starts neighbor2DStrip() earlier.
					g.drawImage(img, ox - neighbor2DStrip(), oy - neighbor2DStrip(), null);
				}
			}
			if (is3D && renderOptions.showGrid && plane > 0)
			{
				drawPlaneGrid3D(g);
			}
			if (is3D && selCorners3D != null)
			{
				drawTileHighlight3D(g);
			}
			if (is3D && showCompass)
			{
				drawCompass(g);
			}
			if (is3D && showBuildCheck && region != null)
			{
				drawBuildCheck3D(g);
			}
			if (is3D && showConflicts && region != null)
			{
				drawConflicts3D(g);
			}
			if (is3D && showHeights && region != null)
			{
				drawHeightLabels3D(g);
			}
			if (!is3D && showSpawns && region != null)
			{
				drawSpawnOverlay(g, ox, oy);
			}
			if (!is3D && selectedLoc != null && region != null)
			{
				drawSameIdFlash(g, ox, oy);
			}
			if (!is3D && selX >= 0)
			{
				g.setColor(Color.YELLOW);
				g.drawRect(ox + selX * tileSize, oy + (63 - selY) * tileSize, tileSize, tileSize);
			}
			// Stamp ghost: when a composite shape is armed, show where it will land under the cursor.
			if (!is3D && pasteTilesMode && tileStamp != null && !tileStamp.isEmpty() && hoverX >= 0)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				for (int[] t : tileStamp)
				{
					int tx = hoverX + t[0], ty = hoverY + t[1];
					if (tx < 0 || tx > 63 || ty < 0 || ty > 63) { continue; }
					int px = ox + tx * tileSize, py = oy + (63 - ty) * tileSize;
					int path = t[4], rot = t[5];
					g2.setColor(new Color(255, 235, 0, 110));
					if (path == 0)
					{
						g2.fillRect(px, py, tileSize, tileSize);
					}
					else
					{
						double[][] poly = MapRenderer.shapePolygon(path, rot, true);
						if (poly != null)
						{
							java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
							for (int i = 0; i < poly.length; i++)
							{
								double vx = px + poly[i][0] * tileSize, vy = py + poly[i][1] * tileSize;
								if (i == 0) { p.moveTo(vx, vy); } else { p.lineTo(vx, vy); }
							}
							p.closePath();
							g2.fill(p);
						}
					}
				}
			}
			// Brush-footprint preview: the whole square the brush will paint.
			if (!is3D && brushSize > 0 && isTerrainTool() && hoverX >= 0 && !areaMode)
			{
				int minX = Math.max(0, hoverX - brushSize), maxX = Math.min(63, hoverX + brushSize);
				int minY = Math.max(0, hoverY - brushSize), maxY = Math.min(63, hoverY + brushSize);
				int px = ox + minX * tileSize;
				int py = oy + (63 - maxY) * tileSize;
				int bw = (maxX - minX + 1) * tileSize;
				int bh = (maxY - minY + 1) * tileSize;
				g.setColor(new Color(255, 235, 0, 45));
				g.fillRect(px, py, bw, bh);
				g.setColor(new Color(255, 235, 0, 200));
				((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(1.5f));
				g.drawRect(px, py, bw, bh);
			}
			if (!is3D && areaMode && areaX0 >= 0)
			{
				int minX = Math.min(areaX0, areaX1), maxX = Math.max(areaX0, areaX1);
				int minY = Math.min(areaY0, areaY1), maxY = Math.max(areaY0, areaY1);
				int px = ox + minX * tileSize;
				int py = oy + (63 - maxY) * tileSize;
				int w = (maxX - minX + 1) * tileSize;
				int h = (maxY - minY + 1) * tileSize;
				g.setColor(new Color(80, 160, 255, 90));
				g.fillRect(px, py, w, h);
				g.setColor(new Color(60, 140, 255));
				g.drawRect(px, py, w, h);
			}
			// Marquee multi-select: the drag box + a highlight on every picked object.
			if (!is3D && marqueeSelecting)
			{
				int minX = Math.min(marqStartX, marqCurX), maxX = Math.max(marqStartX, marqCurX);
				int minY = Math.min(marqStartY, marqCurY), maxY = Math.max(marqStartY, marqCurY);
				int px = ox + minX * tileSize;
				int py = oy + (63 - maxY) * tileSize;
				int w = (maxX - minX + 1) * tileSize;
				int h = (maxY - minY + 1) * tileSize;
				g.setColor(new Color(255, 235, 0, 60));
				g.fillRect(px, py, w, h);
				g.setColor(Color.YELLOW);
				((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(1f));
				g.drawRect(px, py, w, h);
			}
			if (!is3D && !selectedLocs.isEmpty() && region != null)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
				g2.setColor(Color.YELLOW);
				g2.setStroke(new java.awt.BasicStroke(2f));
				for (Location l : selectedLocs)
				{
					if (l.getPosition().getZ() != plane)
					{
						continue;
					}
					g2.drawRect(ox + l.getPosition().getX() * tileSize,
						oy + (63 - l.getPosition().getY()) * tileSize, tileSize, tileSize);
				}
			}
		}

		/** Flash every placement of the selected object's id; solid for the one selected. */
		private void drawSameIdFlash(Graphics g, int ox, int oy)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			g2.setStroke(new java.awt.BasicStroke(2f));
			for (Location l : region.getLocations().getLocations())
			{
				if (l.getPosition().getZ() != plane || l.getId() != selectedLoc.getId())
				{
					continue;
				}
				boolean isSel = l == selectedLoc;
				if (!isSel && !pulseOn)
				{
					continue; // blink the others
				}
				g2.setColor(isSel ? Color.YELLOW : new Color(0xFF8C1A));
				g2.drawRect(ox + l.getPosition().getX() * tileSize,
					oy + (63 - l.getPosition().getY()) * tileSize, tileSize, tileSize);
			}
		}

		private void drawTileHighlight3D(Graphics g)
		{
			double scaleX = getWidth() / (double) view3dW;
			double scaleY = getHeight() / (double) view3dH;
			int[] xs = new int[4], ys = new int[4];
			for (int i = 0; i < 4; i++)
			{
				double[] p = renderer3D.project(selCorners3D[i][0], selCorners3D[i][1], selCorners3D[i][2], camera);
				if (p == null)
				{
					return;
				}
				xs[i] = (int) (p[0] * scaleX);
				ys[i] = (int) (p[1] * scaleY);
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			g2.setColor(new Color(255, 235, 0, 70));
			g2.fillPolygon(xs, ys, 4);
			g2.setColor(new Color(255, 235, 0));
			g2.setStroke(new java.awt.BasicStroke(2f));
			g2.drawPolygon(xs, ys, 4);
		}

		/**
		 * A small N/S/E/W compass in the top-right corner that rotates with the camera so
		 * north always points to true (world) north. Its rotation is -yaw, derived from the
		 * projection: world-north (+Z) lands on screen in direction (sin(-yaw), -cos(-yaw)).
		 */
		private void drawCompass(Graphics g)
		{
			double rot = -camera.yaw;
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
				java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			int r = 32;
			int cx = getWidth() - r - 16;
			int cy = r + 16;
			g2.setColor(new Color(0, 0, 0, 120));
			g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
			g2.setColor(new Color(255, 255, 255, 150));
			g2.setStroke(new java.awt.BasicStroke(1.5f));
			g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
			String[] dirs = {"N", "E", "S", "W"};
			g2.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 13));
			java.awt.FontMetrics fm = g2.getFontMetrics();
			for (int i = 0; i < 4; i++)
			{
				double ang = rot + i * Math.PI / 2.0; // clockwise: N, E, S, W
				double dx = Math.sin(ang), dy = -Math.cos(ang); // screen up = -y
				g2.setColor(new Color(255, 255, 255, i == 0 ? 200 : 70));
				g2.drawLine(cx, cy, (int) (cx + dx * (r - 4)), (int) (cy + dy * (r - 4)));
				g2.setColor(i == 0 ? new Color(0xFF5A5A) : Color.WHITE);
				int lx = (int) (cx + dx * (r - 12));
				int ly = (int) (cy + dy * (r - 12));
				g2.drawString(dirs[i], lx - fm.stringWidth(dirs[i]) / 2f, ly + fm.getAscent() / 2f - 1);
			}
			g2.setColor(new Color(230, 230, 230));
			g2.fillOval(cx - 2, cy - 2, 4, 4);
			g2.dispose();
		}

		/**
		 * Wall-conflict overlay: fills red any tile that has 2+ wall objects (type 0-3 or 9) on the
		 * current plane. OSRS keeps only one wall per tile, so stacked walls go missing in-game —
		 * corners must use a single corner-wall (type 1/2), not two stacked straight walls.
		 */
		private void drawConflicts3D(Graphics g)
		{
			if (planeGridHeights == null || region == null)
			{
				return;
			}
			// Count wall objects per tile on the current plane.
			java.util.Map<Integer, Integer> walls = new java.util.HashMap<>();
			for (Location l : region.getLocations().getLocations())
			{
				if (l.getPosition().getZ() != plane)
				{
					continue;
				}
				int ty = l.getType();
				if ((ty >= 0 && ty <= 3) || ty == 9)
				{
					walls.merge((l.getPosition().getX() << 6) | l.getPosition().getY(), 1, Integer::sum);
				}
			}
			double scaleX = getWidth() / (double) view3dW;
			double scaleY = getHeight() / (double) view3dH;
			final int t = 128;
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setStroke(new java.awt.BasicStroke(2f));
			for (var e : walls.entrySet())
			{
				if (e.getValue() < 2)
				{
					continue; // one wall = fine
				}
				int x = e.getKey() >> 6, y = e.getKey() & 63;
				int[][] c = {{x, y}, {x + 1, y}, {x + 1, y + 1}, {x, y + 1}};
				int[] xs = new int[4], ys = new int[4];
				boolean ok = true;
				for (int i = 0; i < 4; i++)
				{
					double[] p = renderer3D.project(c[i][0] * t, planeGridHeights[c[i][0]][c[i][1]], c[i][1] * t, camera);
					if (p == null) { ok = false; break; }
					xs[i] = (int) (p[0] * scaleX);
					ys[i] = (int) (p[1] * scaleY);
				}
				if (!ok)
				{
					continue;
				}
				g2.setColor(new Color(240, 40, 40, 120));
				g2.fillPolygon(xs, ys, 4);
				g2.setColor(new Color(255, 80, 80));
				g2.drawPolygon(xs, ys, 4);
			}
			g2.dispose();
		}

		/**
		 * Building-check overlay: fills each NON-flat tile red (deeper red = steeper slope), so you
		 * can see at a glance where a building would tilt or float. Flat tiles are left clear.
		 */
		private void drawBuildCheck3D(Graphics g)
		{
			if (planeGridHeights == null)
			{
				return;
			}
			double scaleX = getWidth() / (double) view3dW;
			double scaleY = getHeight() / (double) view3dH;
			final int t = 128;
			int[][] sx = new int[65][65];
			int[][] sy = new int[65][65];
			boolean[][] ok = new boolean[65][65];
			for (int x = 0; x <= 64; x++)
			{
				for (int y = 0; y <= 64; y++)
				{
					double[] p = renderer3D.project(x * t, planeGridHeights[x][y], y * t, camera);
					if (p != null)
					{
						sx[x][y] = (int) (p[0] * scaleX);
						sy[x][y] = (int) (p[1] * scaleY);
						ok[x][y] = true;
					}
				}
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			for (int x = 0; x < 64; x++)
			{
				for (int y = 0; y < 64; y++)
				{
					int h00 = planeGridHeights[x][y], h10 = planeGridHeights[x + 1][y];
					int h01 = planeGridHeights[x][y + 1], h11 = planeGridHeights[x + 1][y + 1];
					int mn = Math.min(Math.min(h00, h10), Math.min(h01, h11));
					int mx = Math.max(Math.max(h00, h10), Math.max(h01, h11));
					if (mx == mn) // perfectly flat — buildable, leave clear
					{
						continue;
					}
					if (!(ok[x][y] && ok[x + 1][y] && ok[x + 1][y + 1] && ok[x][y + 1]))
					{
						continue;
					}
					int steps = (mx - mn) / 8; // difference in height units across the tile
					int alpha = Math.min(170, 55 + steps * 22);
					g2.setColor(new Color(230, 45, 45, alpha));
					int[] xs = {sx[x][y], sx[x + 1][y], sx[x + 1][y + 1], sx[x][y + 1]};
					int[] ys = {sy[x][y], sy[x + 1][y], sy[x + 1][y + 1], sy[x][y + 1]};
					g2.fillPolygon(xs, ys, 4);
				}
			}
			g2.dispose();
		}

		/**
		 * Overlay each tile's height as a number on the 3D view (current plane). The value is
		 * -worldY/8 (rounded) so higher ground reads as a bigger number and equal values mean
		 * equal elevation — handy for matching a bridge (plane 1) to its banks (plane 0).
		 */
		private void drawHeightLabels3D(Graphics g)
		{
			if (planeGridHeights == null)
			{
				return;
			}
			double scaleX = getWidth() / (double) view3dW;
			double scaleY = getHeight() / (double) view3dH;
			final int t = 128;
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 10));
			java.awt.FontMetrics fm = g2.getFontMetrics();
			int w = getWidth(), h = getHeight();
			for (int x = 0; x < 64; x++)
			{
				for (int y = 0; y < 64; y++)
				{
					double hc = (planeGridHeights[x][y] + planeGridHeights[x + 1][y]
						+ planeGridHeights[x][y + 1] + planeGridHeights[x + 1][y + 1]) / 4.0;
					double[] p = renderer3D.project(x * t + t / 2, hc, y * t + t / 2, camera);
					if (p == null)
					{
						continue;
					}
					int sx = (int) (p[0] * scaleX), sy = (int) (p[1] * scaleY);
					if (sx < -20 || sx > w + 20 || sy < -20 || sy > h + 20)
					{
						continue;
					}
					String s = Integer.toString((int) Math.round(-hc / 8.0));
					int tw = fm.stringWidth(s);
					g2.setColor(new Color(0, 0, 0, 190));
					g2.drawString(s, sx - tw / 2 + 1, sy + 1);
					g2.setColor(Color.WHITE);
					g2.drawString(s, sx - tw / 2, sy);
				}
			}
			g2.dispose();
		}

		/**
		 * A translucent white wireframe grid draped over the current plane's floor,
		 * so each level is clearly separated from the dimmed floor(s) below it in 3D.
		 */
		private void drawPlaneGrid3D(Graphics g)
		{
			if (planeGridHeights == null)
			{
				return;
			}
			double scaleX = getWidth() / (double) view3dW;
			double scaleY = getHeight() / (double) view3dH;
			final int t = 128;
			// Project every grid vertex once; null == behind the camera.
			int[][] sx = new int[65][65];
			int[][] sy = new int[65][65];
			boolean[][] ok = new boolean[65][65];
			for (int x = 0; x <= 64; x++)
			{
				for (int y = 0; y <= 64; y++)
				{
					double[] p = renderer3D.project(x * t, planeGridHeights[x][y], y * t, camera);
					if (p != null)
					{
						sx[x][y] = (int) (p[0] * scaleX);
						sy[x][y] = (int) (p[1] * scaleY);
						ok[x][y] = true;
					}
				}
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			g2.setColor(new Color(255, 255, 255, 45));
			g2.setStroke(new java.awt.BasicStroke(1f));
			for (int x = 0; x <= 64; x++)
			{
				for (int y = 0; y <= 64; y++)
				{
					if (x < 64 && ok[x][y] && ok[x + 1][y])
					{
						g2.drawLine(sx[x][y], sy[x][y], sx[x + 1][y], sy[x + 1][y]);
					}
					if (y < 64 && ok[x][y] && ok[x][y + 1])
					{
						g2.drawLine(sx[x][y], sy[x][y], sx[x][y + 1], sy[x][y + 1]);
					}
				}
			}
		}

		private void drawSpawnOverlay(Graphics g, int ox, int oy)
		{
			for (SpawnLoader.Spawn s : service.getSpawnsInRegion(region.getRegionId()))
			{
				if (renderOptions.allPlanes ? (s.z > plane) : (s.z != plane))
				{
					continue;
				}
				int lx = s.x - region.getBaseX();
				int ly = s.y - region.getBaseY();
				if (lx < 0 || lx > 63 || ly < 0 || ly > 63)
				{
					continue;
				}
				int px = ox + lx * tileSize;
				int py = oy + (63 - ly) * tileSize;
				int d = Math.max(5, tileSize - 2);
				Color mark = s.npc ? new Color(190, 60, 220) : new Color(48, 224, 255); // NPC purple, obj cyan
				g.setColor(mark);
				if (s.npc)
				{
					g.fillOval(px, py, d, d);
				}
				else
				{
					g.fillRect(px, py, d, d);
				}
				g.setColor(Color.BLACK);
				if (s.npc)
				{
					g.drawOval(px, py, d, d);
				}
				else
				{
					g.drawRect(px, py, d, d);
				}
				// facing tick (the "-0" direction indicator in the official tool)
				int cx = px + d / 2, cy = py + d / 2, rr = d;
				int ex = cx, ey = cy;
				switch (s.orientation & 3)
				{
					case 1: ex = cx - rr; break; // W
					case 2: ey = cy - rr; break; // N
					case 3: ex = cx + rr; break; // E
					default: ey = cy + rr;       // S
				}
				g.setColor(mark);
				((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(2f));
				g.drawLine(cx, cy, ex, ey);
				// Highlight the selected spawn (yellow) and every other spawn sharing
				// its id (orange), the way selecting an object flashes all its copies.
				if (selectedNpc != null && s.npc == selectedNpc.npc && s.id == selectedNpc.id)
				{
					boolean isSel = s == selectedNpc;
					g.setColor(isSel ? Color.YELLOW : new Color(0xFF8C1A));
					((java.awt.Graphics2D) g).setStroke(new java.awt.BasicStroke(isSel ? 2.5f : 2f));
					g.drawRect(px - 2, py - 2, d + 4, d + 4);
				}
				if (showSpawnNames && tileSize >= 10 && s.name != null)
				{
					g.setColor(Color.WHITE);
					g.drawString(s.name, px + d + 1, py + d);
				}
			}
		}
	}
}
