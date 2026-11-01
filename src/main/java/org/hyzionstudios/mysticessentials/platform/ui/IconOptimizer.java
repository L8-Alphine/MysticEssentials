package org.hyzionstudios.mysticessentials.platform.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Makes a server-supplied PNG fit to ship to every client: no larger than
 * {@link #MAX_EDGE} on its long side, metadata stripped, recompressed.
 *
 * <p>The UI draws these at 20-72 px, so anything past 128 px is bytes every
 * player downloads and nobody sees. Downscaling runs on premultiplied pixels
 * (halving steps, then one bilinear step), so edges keep their colour instead
 * of picking up a dark fringe, and transparent pixels beside the artwork take
 * their neighbours' colour for the same reason at draw time.</p>
 *
 * <p>Pure computation; the caller decides where the result goes. Never writes
 * to the source.</p>
 */
public final class IconOptimizer {

    /** Longest side of a shipped icon. */
    public static final int MAX_EDGE = 128;

    /**
     * @param bytes  what to ship
     * @param width  of the shipped image
     * @param height of the shipped image
     * @param changed whether {@code bytes} differ from the source (false when it was already lean)
     */
    public record Result(byte[] bytes, int width, int height, boolean changed) {
    }

    private IconOptimizer() {
    }

    /** @throws IOException when the bytes are not an image ImageIO can read */
    public static Result optimize(byte[] source) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) {
            throw new IOException("not a readable image");
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        BufferedImage image = convert(decoded, BufferedImage.TYPE_INT_ARGB_PRE);
        boolean resized = false;
        while (Math.max(width, height) > MAX_EDGE * 2) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            image = scale(image, width, height);
            resized = true;
        }
        if (Math.max(width, height) > MAX_EDGE) {
            double factor = (double) MAX_EDGE / Math.max(width, height);
            width = Math.max(1, (int) Math.round(width * factor));
            height = Math.max(1, (int) Math.round(height * factor));
            image = scale(image, width, height);
            resized = true;
        }
        BufferedImage straight = convert(image, BufferedImage.TYPE_INT_ARGB);
        bleed(straight);
        byte[] encoded = encode(straight);
        if (!resized && encoded.length >= source.length) {
            return new Result(source, width, height, false);
        }
        return new Result(encoded, width, height, true);
    }

    private static BufferedImage convert(BufferedImage source, int type) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Transparent pixels touching the artwork take its colour (two rings); the rest go to zero. */
    static void bleed(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        boolean[] solid = new boolean[argb.length];
        for (int i = 0; i < argb.length; i++) {
            solid[i] = (argb[i] >>> 24) != 0;
        }
        for (int pass = 0; pass < 2; pass++) {
            int[] next = argb.clone();
            boolean[] grown = solid.clone();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (solid[index]) {
                        continue;
                    }
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    int n = 0;
                    for (int ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1); ny++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                            int j = ny * width + nx;
                            if (solid[j]) {
                                r += (argb[j] >> 16) & 0xFF;
                                g += (argb[j] >> 8) & 0xFF;
                                b += argb[j] & 0xFF;
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        next[index] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
                        grown[index] = true;
                    }
                }
            }
            argb = next;
            solid = grown;
        }
        for (int i = 0; i < argb.length; i++) {
            if (!solid[i]) {
                argb[i] = 0;
            }
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("no PNG writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                // Quality 0 is the PNG writer's strongest deflate; PNG stays lossless.
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.0f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
