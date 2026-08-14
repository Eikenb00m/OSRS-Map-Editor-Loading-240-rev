/*
 * Entry point for the OSRS map editor.
 *
 * Usage:
 *   java net.runelite.cache.editor.MapEditor [--cache <dir>] [--xteas <file>] [--region <id>]
 *
 * If --cache is omitted a folder chooser is shown. XTEA keys default to
 * region_keys.json / xteas.json found in the cache folder or its parent; if none
 * is found a file chooser is shown (Cancel to continue without keys). Region
 * defaults to 12850 (Lumbridge) or the first available region.
 */
package net.runelite.cache.editor;

import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class MapEditor
{
	public static void main(String[] args) throws Exception
	{
		// Quiet the per-definition opcode warnings from the object loader.
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

		File cacheDir = null;
		File xteasFile = null;
		int region = 12850;

		for (int i = 0; i < args.length - 1; i++)
		{
			switch (args[i])
			{
				case "--cache": cacheDir = new File(args[++i]); break;
				case "--xteas": xteasFile = new File(args[++i]); break;
				case "--region": region = Integer.parseInt(args[++i]); break;
				default:
			}
		}

		try
		{
			// Modern flat dark theme; fall back to the system L&F if unavailable.
			com.formdev.flatlaf.FlatDarkLaf.setup();
			java.awt.Color accent = new java.awt.Color(0x4C8BF5);
			UIManager.put("Component.focusWidth", 1);
			UIManager.put("Component.arc", 10);
			UIManager.put("Button.arc", 10);
			UIManager.put("TextComponent.arc", 8);
			UIManager.put("ScrollBar.thumbArc", 999);
			UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
			UIManager.put("ScrollBar.width", 12);
			UIManager.put("TabbedPane.showTabSeparators", true);
			UIManager.put("TabbedPane.tabHeight", 30);
			UIManager.put("TabbedPane.selectedBackground", new java.awt.Color(0x30343B));
			UIManager.put("Component.accentColor", accent);
			UIManager.put("Button.default.focusColor", accent);
			UIManager.put("ToggleButton.selectedBackground", accent);
			UIManager.put("ToggleButton.selectedForeground", java.awt.Color.WHITE);
			UIManager.put("ToolBar.separatorColor", new java.awt.Color(0x3A3F47));
			UIManager.put("Panel.background", new java.awt.Color(0x25282D));
			UIManager.put("ToolBar.background", new java.awt.Color(0x2B2F35));
			UIManager.put("defaultFont",
				new javax.swing.plaf.FontUIResource("Segoe UI", java.awt.Font.PLAIN, 12));
		}
		catch (Throwable t)
		{
			try
			{
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			}
			catch (Exception ignored)
			{
			}
		}

		// Remembered choices from last run, so the cache/keys don't have to be re-picked.
		File configFile = new File(System.getProperty("user.home"), ".osrs-map-editor.properties");
		java.util.Properties prefs = new java.util.Properties();
		if (configFile.exists())
		{
			try (java.io.InputStream in = new java.io.FileInputStream(configFile))
			{
				prefs.load(in);
			}
			catch (Exception ignored)
			{
			}
		}

		// No --cache given: reuse the last cache folder if it's still valid.
		if (cacheDir == null || !cacheDir.isDirectory())
		{
			String saved = prefs.getProperty("cache");
			if (saved != null && new File(saved, "main_file_cache.dat2").exists())
			{
				cacheDir = new File(saved);
			}
		}

		if (cacheDir == null || !cacheDir.isDirectory())
		{
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setDialogTitle("Select cache folder (contains main_file_cache.dat2)");
			if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			cacheDir = fc.getSelectedFile();
		}

		// Reuse the remembered keys file, else auto-locate.
		if (xteasFile == null)
		{
			String savedKeys = prefs.getProperty("xteas");
			if (savedKeys != null && new File(savedKeys).exists())
			{
				xteasFile = new File(savedKeys);
			}
		}
		if (xteasFile == null)
		{
			xteasFile = findXteas(cacheDir);
		}

		// Not auto-found (or a bad --xteas path): let the user pick it, or skip.
		if (xteasFile == null || !xteasFile.exists())
		{
			File start = cacheDir.getParentFile() != null ? cacheDir.getParentFile() : cacheDir;
			JFileChooser fc = new JFileChooser(start);
			fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
			fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
				"XTEA key files (*.json)", "json"));
			fc.setDialogTitle("Select XTEA keys file (region_keys.json / xteas.json) — Cancel to continue without");
			xteasFile = fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
		}

		// Remember these for next launch so the choosers can be skipped.
		prefs.setProperty("cache", cacheDir.getAbsolutePath());
		if (xteasFile != null)
		{
			prefs.setProperty("xteas", xteasFile.getAbsolutePath());
		}
		try (java.io.OutputStream out = new java.io.FileOutputStream(configFile))
		{
			prefs.store(out, "OSRS Map Editor — last cache/keys");
		}
		catch (Exception ignored)
		{
		}

		JsonXteaKeyProvider keys = new JsonXteaKeyProvider(xteasFile);
		MapEditorService service = new MapEditorService(cacheDir, keys);
		try
		{
			service.open();
		}
		catch (Exception ex)
		{
			JOptionPane.showMessageDialog(null, "Failed to open cache:\n" + ex.getMessage(),
				"Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (!service.regionExists(region))
		{
			java.util.List<Integer> regions = service.listRegions();
			if (!regions.isEmpty())
			{
				region = regions.get(0);
			}
		}

		final int startRegion = region;
		SwingUtilities.invokeLater(() -> new MapEditorFrame(service, startRegion).setVisible(true));
	}

	/**
	 * Auto-locate an XTEA key file for a cache: region_keys.json (OpenRS2) then
	 * xteas.json, in the cache folder then its parent (the server's data/ dir).
	 * Returns null if none is found.
	 */
	static File findXteas(File cacheDir)
	{
		String[] names = {"region_keys.json", "xteas.json"};
		File[] dirs = {cacheDir, cacheDir.getParentFile()};
		for (File dir : dirs)
		{
			if (dir == null)
			{
				continue;
			}
			for (String name : names)
			{
				File candidate = new File(dir, name);
				if (candidate.exists())
				{
					return candidate;
				}
			}
		}
		return null;
	}
}
