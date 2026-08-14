/*
 * Minimal software 3D renderer: a z-buffered triangle rasterizer with an
 * orbiting perspective camera and optional per-face texture mapping. No native
 * dependencies — it draws into a BufferedImage using a float depth buffer.
 *
 * World convention: X east, Z north, Y up-is-negative (matching the cache's
 * tile heights and model vertices, where smaller Y is higher).
 */
package net.runelite.cache.editor;

import java.awt.image.BufferedImage;
import java.util.Arrays;

public class Renderer3D
{
	/** Supplies baked texture pixels (size x size, RGB) for a texture id. */
	public interface TextureSource
	{
		int[] pixels(int textureId);

		int size();
	}

	/** Compact triangle soup: parallel arrays, pre-lit. Textured tris carry UVs. */
	public static class Scene
	{
		int count;
		float[] ax = new float[4096], ay = new float[4096], az = new float[4096];
		float[] bx = new float[4096], by = new float[4096], bz = new float[4096];
		float[] cx = new float[4096], cy = new float[4096], cz = new float[4096];
		int[] rgb = new int[4096];          // flat colour (texId < 0) OR fallback avg
		int[] texId = new int[4096];        // -1 == flat
		float[] shade = new float[4096];    // lighting multiplier for textured tris
		float[] u0 = new float[4096], v0 = new float[4096];
		float[] u1 = new float[4096], v1 = new float[4096];
		float[] u2 = new float[4096], v2 = new float[4096];
		// Per-vertex light multipliers for smooth (Gouraud) shading. When smooth[t] is
		// set, the rasterizer interpolates g0/g1/g2 across the triangle instead of using
		// the single per-face shade — this melts the facets on terrain slopes/steps.
		boolean[] smooth = new boolean[4096];
		float[] g0 = new float[4096], g1 = new float[4096], g2 = new float[4096];

		public void add(float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, int color)
		{
			ensure();
			put(x0, y0, z0, x1, y1, z1, x2, y2, z2);
			rgb[count] = color;
			texId[count] = -1;
			smooth[count] = false;
			count++;
		}

		/** Flat-coloured triangle with smooth per-vertex lighting (l0/l1/l2 are 0..~1.2). */
		public void addSmooth(float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, int color, float l0, float l1, float l2)
		{
			ensure();
			put(x0, y0, z0, x1, y1, z1, x2, y2, z2);
			rgb[count] = color;
			texId[count] = -1;
			smooth[count] = true;
			g0[count] = l0; g1[count] = l1; g2[count] = l2;
			count++;
		}

		/** Textured triangle with smooth per-vertex lighting. */
		public void addSmoothTextured(float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, int tex, int fallbackRgb,
			float tu0, float tv0, float tu1, float tv1, float tu2, float tv2,
			float l0, float l1, float l2)
		{
			ensure();
			put(x0, y0, z0, x1, y1, z1, x2, y2, z2);
			rgb[count] = fallbackRgb;
			texId[count] = tex;
			smooth[count] = true;
			g0[count] = l0; g1[count] = l1; g2[count] = l2;
			u0[count] = tu0; v0[count] = tv0;
			u1[count] = tu1; v1[count] = tv1;
			u2[count] = tu2; v2[count] = tv2;
			count++;
		}

		public void addTextured(float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, int tex, float shadeFactor, int fallbackRgb,
			float tu0, float tv0, float tu1, float tv1, float tu2, float tv2)
		{
			ensure();
			put(x0, y0, z0, x1, y1, z1, x2, y2, z2);
			rgb[count] = fallbackRgb;
			texId[count] = tex;
			shade[count] = shadeFactor;
			u0[count] = tu0; v0[count] = tv0;
			u1[count] = tu1; v1[count] = tv1;
			u2[count] = tu2; v2[count] = tv2;
			count++;
		}

		public int size()
		{
			return count;
		}

		/**
		 * Drops every triangle added after index {@code n}, keeping the first {@code n}. Lets the
		 * animation loop reuse a cached static scene and only re-append the animated objects each tick.
		 */
		public void truncateTo(int n)
		{
			if (n >= 0 && n <= count)
			{
				count = n;
			}
		}

		/**
		 * Darkens every triangle added since index {@code from} and drops its
		 * texture, so ghosted lower planes read as a dim backdrop behind the
		 * plane currently being edited.
		 */
		public void dimFrom(int from, float factor)
		{
			for (int i = from; i < count; i++)
			{
				int c = rgb[i];
				int r = (int) (((c >> 16) & 0xFF) * factor);
				int gg = (int) (((c >> 8) & 0xFF) * factor);
				int b = (int) ((c & 0xFF) * factor);
				rgb[i] = (r << 16) | (gg << 8) | b;
				texId[i] = -1;
			}
		}

		/**
		 * Appends triangles from {@code src} whose centroid falls in [minX,maxX]×[minZ,maxZ]
		 * (source world coords), translated by (dx,dz) and dimmed by {@code dim}. Used to show
		 * a dimmed edge strip of a neighbouring region for lining up region boundaries.
		 */
		public void appendClipped(Scene src, float dx, float dz,
			float minX, float maxX, float minZ, float maxZ, float dim)
		{
			for (int i = 0; i < src.count; i++)
			{
				float cxx = (src.ax[i] + src.bx[i] + src.cx[i]) / 3f;
				float czz = (src.az[i] + src.bz[i] + src.cz[i]) / 3f;
				if (cxx < minX || cxx > maxX || czz < minZ || czz > maxZ)
				{
					continue;
				}
				ensure();
				ax[count] = src.ax[i] + dx; ay[count] = src.ay[i]; az[count] = src.az[i] + dz;
				bx[count] = src.bx[i] + dx; by[count] = src.by[i]; bz[count] = src.bz[i] + dz;
				cx[count] = src.cx[i] + dx; cy[count] = src.cy[i]; cz[count] = src.cz[i] + dz;
				int c = src.rgb[i];
				int r = (int) (((c >> 16) & 0xFF) * dim);
				int gg = (int) (((c >> 8) & 0xFF) * dim);
				int b = (int) ((c & 0xFF) * dim);
				rgb[count] = (r << 16) | (gg << 8) | b;
				texId[count] = -1;                 // drop texture; a flat dim colour reads as backdrop
				smooth[count] = src.smooth[i];
				g0[count] = src.g0[i]; g1[count] = src.g1[i]; g2[count] = src.g2[i];
				count++;
			}
		}

		private void put(float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2)
		{
			ax[count] = x0; ay[count] = y0; az[count] = z0;
			bx[count] = x1; by[count] = y1; bz[count] = z1;
			cx[count] = x2; cy[count] = y2; cz[count] = z2;
		}

		private void ensure()
		{
			if (count < rgb.length)
			{
				return;
			}
			int n = rgb.length * 2;
			ax = Arrays.copyOf(ax, n); ay = Arrays.copyOf(ay, n); az = Arrays.copyOf(az, n);
			bx = Arrays.copyOf(bx, n); by = Arrays.copyOf(by, n); bz = Arrays.copyOf(bz, n);
			cx = Arrays.copyOf(cx, n); cy = Arrays.copyOf(cy, n); cz = Arrays.copyOf(cz, n);
			rgb = Arrays.copyOf(rgb, n); texId = Arrays.copyOf(texId, n); shade = Arrays.copyOf(shade, n);
			u0 = Arrays.copyOf(u0, n); v0 = Arrays.copyOf(v0, n);
			u1 = Arrays.copyOf(u1, n); v1 = Arrays.copyOf(v1, n);
			u2 = Arrays.copyOf(u2, n); v2 = Arrays.copyOf(v2, n);
			smooth = Arrays.copyOf(smooth, n);
			g0 = Arrays.copyOf(g0, n); g1 = Arrays.copyOf(g1, n); g2 = Arrays.copyOf(g2, n);
		}
	}

	public static class Camera
	{
		public double yaw = 0.6;
		public double pitch = 0.9;
		public double zoom = 1.0;
		public double cx, cy, cz;
		public double distance = 4000;
	}

	public static boolean backfaceCull = true;

	private final int width, height;
	private final float[] zbuf;
	private final int[] pixels;
	private final int background;
	// One reusable image whose data buffer IS the pixels[] array, so render() writes straight
	// into the displayed image with no per-frame allocation or setRGB colour conversion — the
	// single biggest per-frame cost in a software renderer this size.
	private final BufferedImage img;

	private TextureSource textures;
	private int texSize;

	public Renderer3D(int width, int height, int background)
	{
		this.width = width;
		this.height = height;
		this.zbuf = new float[width * height];
		this.pixels = new int[width * height];
		this.background = background;

		java.awt.image.DataBufferInt db = new java.awt.image.DataBufferInt(pixels, pixels.length);
		java.awt.image.WritableRaster raster = java.awt.image.Raster.createPackedRaster(
			db, width, height, width, new int[]{0xFF0000, 0x00FF00, 0x0000FF}, null);
		java.awt.image.ColorModel cm = new java.awt.image.DirectColorModel(24, 0xFF0000, 0x00FF00, 0x0000FF);
		this.img = new BufferedImage(cm, raster, false, null);
	}

	/** Project a world point to render-buffer screen coords, or null if behind the camera. */
	public double[] project(double wx, double wy, double wz, Camera cam)
	{
		double cosY = Math.cos(cam.yaw), sinY = Math.sin(cam.yaw);
		double cosP = Math.cos(cam.pitch), sinP = Math.sin(cam.pitch);
		double focal = height * 0.9 * cam.zoom;
		double halfW = width / 2.0, halfH = height / 2.0;
		double dx = wx - cam.cx, dy = wy - cam.cy, dz = wz - cam.cz;
		double rx = dx * cosY - dz * sinY;
		double rz = dx * sinY + dz * cosY;
		double up = -dy;
		double ry2 = up * cosP + rz * sinP;
		double rz2 = rz * cosP - up * sinP;
		double depth = rz2 + cam.distance;
		if (depth < 1)
		{
			return null;
		}
		return new double[]{halfW + rx * focal / depth, halfH - ry2 * focal / depth};
	}

	public BufferedImage render(Scene scene, Camera cam)
	{
		return render(scene, cam, null);
	}

	public BufferedImage render(Scene scene, Camera cam, TextureSource texSource)
	{
		this.textures = texSource;
		this.texSize = texSource != null ? texSource.size() : 0;

		Arrays.fill(pixels, background);
		Arrays.fill(zbuf, Float.MAX_VALUE);

		double cosY = Math.cos(cam.yaw), sinY = Math.sin(cam.yaw);
		double cosP = Math.cos(cam.pitch), sinP = Math.sin(cam.pitch);
		double focal = height * 0.9 * cam.zoom;
		double halfW = width / 2.0, halfH = height / 2.0;

		double[] sx = new double[3], sy = new double[3], sz = new double[3];

		for (int t = 0; t < scene.count; t++)
		{
			boolean ok = true;
			for (int i = 0; i < 3; i++)
			{
				double vx, vy, vz;
				switch (i)
				{
					case 0: vx = scene.ax[t]; vy = scene.ay[t]; vz = scene.az[t]; break;
					case 1: vx = scene.bx[t]; vy = scene.by[t]; vz = scene.bz[t]; break;
					default: vx = scene.cx[t]; vy = scene.cy[t]; vz = scene.cz[t];
				}
				double dx = vx - cam.cx;
				double dy = vy - cam.cy;
				double dz = vz - cam.cz;
				double rx = dx * cosY - dz * sinY;
				double rz = dx * sinY + dz * cosY;
				double up = -dy;
				double ry2 = up * cosP + rz * sinP;
				double rz2 = rz * cosP - up * sinP;

				double depth = rz2 + cam.distance;
				if (depth < 1)
				{
					ok = false;
					break;
				}
				sx[i] = halfW + rx * focal / depth;
				sy[i] = halfH - ry2 * focal / depth;
				sz[i] = depth;
			}
			if (ok)
			{
				fillTriangle(scene, t, sx, sy, sz);
			}
		}

		return img;
	}

	private void fillTriangle(Scene s, int t, double[] sx, double[] sy, double[] sz)
	{
		double x0 = sx[0], y0 = sy[0], x1 = sx[1], y1 = sy[1], x2 = sx[2], y2 = sy[2];

		double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (area == 0)
		{
			return;
		}
		if (backfaceCull && area > 0)
		{
			return;
		}
		double invArea = 1.0 / area;

		int minX = (int) Math.floor(Math.min(x0, Math.min(x1, x2)));
		int maxX = (int) Math.ceil(Math.max(x0, Math.max(x1, x2)));
		int minY = (int) Math.floor(Math.min(y0, Math.min(y1, y2)));
		int maxY = (int) Math.ceil(Math.max(y0, Math.max(y1, y2)));
		if (minX < 0) minX = 0;
		if (minY < 0) minY = 0;
		if (maxX >= width) maxX = width - 1;
		if (maxY >= height) maxY = height - 1;

		int tex = s.texId[t];
		int[] texPx = (tex >= 0 && textures != null) ? textures.pixels(tex) : null;
		int flat = s.rgb[t];

		for (int py = minY; py <= maxY; py++)
		{
			for (int px = minX; px <= maxX; px++)
			{
				double fx = px + 0.5, fy = py + 0.5;
				double w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) * invArea;
				double w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) * invArea;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}
				float depth = (float) (w0 * sz[0] + w1 * sz[1] + w2 * sz[2]);
				int idx = py * width + px;
				if (depth >= zbuf[idx])
				{
					continue;
				}

				// Smooth (Gouraud) light interpolated from the triangle's per-vertex values.
				float lf = s.smooth[t]
					? (float) (w0 * s.g0[t] + w1 * s.g1[t] + w2 * s.g2[t]) : -1f;
				int color;
				if (texPx != null)
				{
					double u = w0 * s.u0[t] + w1 * s.u1[t] + w2 * s.u2[t];
					double v = w0 * s.v0[t] + w1 * s.v1[t] + w2 * s.v2[t];
					int tu = (int) (u * (texSize - 1));
					int tv = (int) (v * (texSize - 1));
					if (tu < 0) tu = 0; else if (tu >= texSize) tu = texSize - 1;
					if (tv < 0) tv = 0; else if (tv >= texSize) tv = texSize - 1;
					int texel = texPx[tv * texSize + tu];
					// OSRS textures key transparency to black (0x000000) — e.g. the magic-tree
					// sparkle overlay (tex 34) is ~94% black over green leaves. Skip transparent
					// texels so the geometry behind shows through instead of an opaque blob.
					if ((texel & 0xFFFFFF) == 0) continue;
					color = shadeColor(texel, lf >= 0 ? lf : s.shade[t]);
				}
				else
				{
					color = lf >= 0 ? shadeColor(flat, lf) : flat;
				}

				zbuf[idx] = depth;
				pixels[idx] = color;
			}
		}
	}

	private static int shadeColor(int rgb, float f)
	{
		int r = (int) (((rgb >> 16) & 0xFF) * f);
		int g = (int) (((rgb >> 8) & 0xFF) * f);
		int b = (int) ((rgb & 0xFF) * f);
		if (r > 255) r = 255;
		if (g > 255) g = 255;
		if (b > 255) b = 255;
		return (r << 16) | (g << 8) | b;
	}
}
