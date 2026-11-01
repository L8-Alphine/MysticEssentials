package org.hyzionstudios.mysticessentials.platform.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Checks of the server-icon pipeline without a server: what the optimizer does
 * to big, small and transparent images, and which file names become
 * {@code custom:} names.
 */
public final class IconPipelineTest {

    public static void main(String[] args) throws Exception {
        bigImagesShrinkToTheCap();
        leanImagesPassThrough();
        edgesTakeTheArtworkColour();
        names();
        System.out.println("IconPipelineTest: all checks passed.");
    }

    private static void bigImagesShrinkToTheCap() throws Exception {
        byte[] big = png(solidSquare(1024, 600, 0xFF67A5DA));
        IconOptimizer.Result result = IconOptimizer.optimize(big);
        require(result.changed(), "a 1024 px image is rewritten");
        require(result.width() == IconOptimizer.MAX_EDGE && result.height() == 75,
                "the long side lands on the cap, aspect kept: " + result.width() + "x" + result.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        require(decoded.getWidth() == result.width(), "the bytes are the size reported");
        int centre = decoded.getRGB(64, 37);
        require(((centre >> 16) & 0xFF) == 0x67 && (centre & 0xFF) == 0xDA, "colour survives: " + Integer.toHexString(centre));
    }

    private static void leanImagesPassThrough() throws Exception {
        byte[] small = png(solidSquare(16, 16, 0xFFD9B572));
        IconOptimizer.Result result = IconOptimizer.optimize(small);
        require(result.width() == 16, "small images keep their size");
        require(result.bytes().length <= small.length, "never larger than the source");
    }

    private static void edgesTakeTheArtworkColour() {
        BufferedImage image = new BufferedImage(5, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        IconOptimizer.bleed(image);
        int next = image.getRGB(1, 0);
        require((next >>> 24) == 0 && ((next >> 16) & 0xFF) == 0xFF, "the pixel beside the artwork is red but transparent");
        require(image.getRGB(4, 0) == 0, "pixels far from the artwork are cleared");
    }

    private static void names() throws Exception {
        Path folder = Files.createTempDirectory("icons");
        require(CustomIcons.nameOf(folder, folder.resolve("castle.png")).equals("castle"), "a file at the top");
        require(CustomIcons.nameOf(folder, folder.resolve("models").resolve("dragon_1.png")).equals("models/dragon_1"),
                "a subfolder becomes part of the name");
        require(CustomIcons.nameOf(folder, folder.resolve("my icon.png")).isEmpty(), "spaces are refused");
        require(CustomIcons.isPng(png(solidSquare(2, 2, 0xFFFFFFFF))) && !CustomIcons.isPng(new byte[] {1, 2, 3}),
                "PNGs are recognised by their signature");
    }

    private static BufferedImage solidSquare(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
