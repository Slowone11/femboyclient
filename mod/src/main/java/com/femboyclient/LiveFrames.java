package com.femboyclient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Turns one screen picture (pixels as 0xAARRGGBB) into a small JPEG for the live view. Free of Minecraft classes so it can be
 * tested. Slow enough (a few milliseconds) that it must run on a background thread, never the game thread.
 */
final class LiveFrames {

	private LiveFrames() {
	}

	/** The size the picture is sent at: no wider than maxWidth, the same shape as the screen, never bigger than the screen. */
	static int[] sizeFor(int width, int height, int maxWidth) {
		int outWidth = Math.max(1, Math.min(width, maxWidth));
		int outHeight = Math.max(1, (int) Math.round(height * (outWidth / (double) width)));
		return new int[] {outWidth, outHeight};
	}

	/** Shrinks by averaging every block of source pixels that lands on one new pixel, so text stays readable. Alpha is dropped. */
	static int[] shrink(int[] argb, int width, int height, int outWidth, int outHeight) {
		int[] out = new int[outWidth * outHeight];
		for (int y = 0; y < outHeight; y++) {
			int y0 = (int) ((long) y * height / outHeight);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * height / outHeight));
			for (int x = 0; x < outWidth; x++) {
				int x0 = (int) ((long) x * width / outWidth);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * width / outWidth));
				long r = 0, g = 0, b = 0;
				for (int sy = y0; sy < y1; sy++) {
					int row = sy * width;
					for (int sx = x0; sx < x1; sx++) {
						int p = argb[row + sx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
					}
				}
				int n = (y1 - y0) * (x1 - x0);
				out[y * outWidth + x] = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
			}
		}
		return out;
	}

	/** The JPEG of a picture, shrunk to at most maxWidth wide. quality is 0 to 1. */
	static byte[] encode(int[] argb, int width, int height, int maxWidth, float quality) throws IOException {
		if (width <= 0 || height <= 0 || argb.length < width * height) {
			throw new IOException("bad picture size " + width + "x" + height);
		}
		int[] size = sizeFor(width, height, maxWidth);
		int[] small = size[0] == width && size[1] == height ? argb : shrink(argb, width, height, size[0], size[1]);

		BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, size[0], size[1], small, 0, size[0]);

		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(48_000);
			try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
				writer.setOutput(out);
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return bytes.toByteArray();
		} finally {
			writer.dispose();
		}
	}
}
