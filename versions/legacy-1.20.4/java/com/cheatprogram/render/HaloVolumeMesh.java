package com.cheatprogram.render;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.Arrays;

/** Small CPU silhouettes, produced before upload; no GPU readback or per-frame bitmap work. */
final class HaloVolumeMesh {
	static final int SAMPLE_SIZE = 128;
	record Sample(int[] colors) {
		int color(int x, int y) { return colors[Math.max(0, Math.min(SAMPLE_SIZE - 1, y)) * SAMPLE_SIZE + Math.max(0, Math.min(SAMPLE_SIZE - 1, x))]; }
		int uv(float u, float v) { return color((int)(u * SAMPLE_SIZE), (int)(v * SAMPLE_SIZE)); }
	}
	record Mesh(float[] positions, int[] colors) { boolean empty() { return colors.length == 0; } }
	private HaloVolumeMesh() { }
	static Sample sample(NativeImage image) {
		int n = SAMPLE_SIZE * SAMPLE_SIZE;
		long[] red = new long[n], green = new long[n], blue = new long[n], weight = new long[n];
		int[] alpha = new int[n];
		int width = image.getWidth(), height = image.getHeight();
		for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
			int color = image.getPixelRGBA(x, y), a = color >>> 24;
			if (a == 0) continue;
			int i = (y * SAMPLE_SIZE / height) * SAMPLE_SIZE + x * SAMPLE_SIZE / width;
			alpha[i] = Math.max(alpha[i], a); weight[i] += a;
			red[i] += (long)(color >>> 16 & 255) * a; green[i] += (long)(color >>> 8 & 255) * a; blue[i] += (long)(color & 255) * a;
		}
		int[] colors = new int[n];
		for (int y = 0; y < SAMPLE_SIZE; y++) for (int x = 0; x < SAMPLE_SIZE; x++) {
			int i = y * SAMPLE_SIZE + x;
			if (weight[i] > 0) colors[i] = alpha[i] << 24 | (int)(red[i] / weight[i]) << 16 | (int)(green[i] / weight[i]) << 8 | (int)(blue[i] / weight[i]);
			// Small source textures are enlarged with nearest cells, without inventing empty seams.
			else if (width < SAMPLE_SIZE || height < SAMPLE_SIZE) colors[i] = image.getPixelRGBA(Math.min(width - 1, x * width / SAMPLE_SIZE), Math.min(height - 1, y * height / SAMPLE_SIZE));
		}
		return new Sample(colors);
	}
	/** X/Z occupy [-.5,.5]; Y is normalised thickness. Interior walls are omitted. */
	static Mesh silhouette(Sample source, int resolution, boolean pixel) {
		int n = Math.max(8, Math.min(SAMPLE_SIZE, resolution));
		int[] cells = new int[n * n];
		for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int x0 = x * SAMPLE_SIZE / n, x1 = (x + 1) * SAMPLE_SIZE / n;
			int y0 = y * SAMPLE_SIZE / n, y1 = (y + 1) * SAMPLE_SIZE / n;
			long r = 0, g = 0, b = 0, weight = 0; int alpha = 0;
			for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
				int c = source.color(xx, yy), a = c >>> 24;
				alpha = Math.max(alpha, a); weight += a;
				r += (long)(c >>> 16 & 255) * a; g += (long)(c >>> 8 & 255) * a; b += (long)(c & 255) * a;
			}
			if (weight != 0 && alpha >= 32) cells[y * n + x] = alpha << 24 | (int)(r / weight) << 16 | (int)(g / weight) << 8 | (int)(b / weight);
		}
		Builder out = new Builder();
		for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int color = cells[y * n + x];
			if (color == 0) continue;
			float x0 = (float)x / n - .5f, x1 = (float)(x + 1) / n - .5f;
			// Image top maps to positive Z, exactly like the original texture quad.
			float z0 = .5f - (float)(y + 1) / n, z1 = .5f - (float)y / n;
			if (pixel) {
				out.quad(color, x0,.5f,z0, x0,.5f,z1, x1,.5f,z1, x1,.5f,z0);
				out.quad(shade(color,.8f), x0,-.5f,z0, x1,-.5f,z0, x1,-.5f,z1, x0,-.5f,z1);
			}
			if (x == 0 || cells[y*n+x-1] == 0) out.quad(shade(color,.72f),x0,-.5f,z0,x0,-.5f,z1,x0,.5f,z1,x0,.5f,z0);
			if (x == n-1 || cells[y*n+x+1] == 0) out.quad(shade(color,.82f),x1,-.5f,z1,x1,-.5f,z0,x1,.5f,z0,x1,.5f,z1);
			if (y == 0 || cells[(y-1)*n+x] == 0) out.quad(shade(color,.86f),x0,-.5f,z1,x1,-.5f,z1,x1,.5f,z1,x0,.5f,z1);
			if (y == n-1 || cells[(y+1)*n+x] == 0) out.quad(shade(color,.68f),x1,-.5f,z0,x0,-.5f,z0,x0,.5f,z0,x1,.5f,z0);
		}
		return out.build();
	}
	static Mesh pixelRing(Sample outer, Sample inner, HaloModelLibrary.Primitive p, float thickness, int resolution) {
		Builder out = new Builder();
		int count = Math.max(8, Math.min(64, resolution)), rows = 8;
		float radial = Math.max(p.width() * thickness, p.width() * .01f);
		float ro = p.width() + radial / 2, ri = Math.max(p.width() * .05f, p.width() - radial / 2);
		float height = Math.max(p.height(), radial), half = height / 2;
		for (int i = 0; i < count; i++) {
			float u0 = (float)i / count, u1 = (float)(i+1) / count, u = (u0 + u1) / 2;
			float c0 = (float)Math.cos(u0 * Math.PI * 2), s0 = (float)Math.sin(u0 * Math.PI * 2);
			float c1 = (float)Math.cos(u1 * Math.PI * 2), s1 = (float)Math.sin(u1 * Math.PI * 2);
			for (int row = 0; row < rows; row++) {
				float v0 = (float)row / rows, v1 = (float)(row+1) / rows;
				float y0 = half - height*v0, y1 = half - height*v1;
				int a = outer.uv(u,(v0+v1)/2), b = inner.uv(u,(v0+v1)/2);
				if ((a >>> 24) >= 32) out.quad(shade(a,.86f),ro*c0,y0,ro*s0,ro*c1,y0,ro*s1,ro*c1,y1,ro*s1,ro*c0,y1,ro*s0);
				if ((b >>> 24) >= 32) out.quad(shade(b,.72f),ri*c1,y0,ri*s1,ri*c0,y0,ri*s0,ri*c0,y1,ri*s0,ri*c1,y1,ri*s1);
			}
			int top = outer.uv(u,.0625f), bottom = outer.uv(u,.9375f);
			if ((top >>> 24) >= 32) out.quad(top,ro*c0,half,ro*s0,ri*c0,half,ri*s0,ri*c1,half,ri*s1,ro*c1,half,ro*s1);
			if ((bottom >>> 24) >= 32) out.quad(shade(bottom,.8f),ro*c1,-half,ro*s1,ri*c1,-half,ri*s1,ri*c0,-half,ri*s0,ro*c0,-half,ro*s0);
		}
		return out.build();
	}
	private static int shade(int c, float s) { return c & 0xFF000000 | (int)((c >>> 16 & 255)*s) << 16 | (int)((c >>> 8 & 255)*s) << 8 | (int)((c & 255)*s); }
	static final class Builder {
		private float[] positions = new float[768]; private int[] colors = new int[256]; private int size;
		void quad(int color, float... xyz) {
			if (size + 4 > colors.length) { colors = Arrays.copyOf(colors, colors.length*2); positions = Arrays.copyOf(positions, colors.length*3); }
			System.arraycopy(xyz,0,positions,size*3,12); Arrays.fill(colors,size,size+4,color); size+=4;
		}
		Mesh build() { return new Mesh(Arrays.copyOf(positions,size*3),Arrays.copyOf(colors,size)); }
	}
}
