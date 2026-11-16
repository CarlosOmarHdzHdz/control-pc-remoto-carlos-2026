package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class FrameEncoder implements AutoCloseable {
    private final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    private final ImageWriteParam parameters = writer.getDefaultWriteParam();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(192 * 1024);
    private BufferedImage scaled;

    FrameEncoder() { parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); }

    void encode(BufferedImage source, int resolution, boolean automatic) throws IOException {
        int[] size = fit(source.getWidth(), source.getHeight(), resolution);
        BufferedImage image = source;
        if (size[0] != source.getWidth() || size[1] != source.getHeight()) {
            if (scaled == null || scaled.getWidth() != size[0] || scaled.getHeight() != size[1]) {
                if (scaled != null) scaled.flush();
                scaled = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            }
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, size[0], size[1], null);
            } finally { graphics.dispose(); }
            image = scaled;
        }
        bytes.reset();
        float quality = automatic ? (0.54f + resolution * 0.05f) : 0.81f;
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally { writer.setOutput(null); }
    }

    static int[] fit(int width, int height, int resolution) {
        int limitWidth = width >= height ? Wire.WIDTHS[resolution] : Wire.HEIGHTS[resolution];
        int limitHeight = width >= height ? Wire.HEIGHTS[resolution] : Wire.WIDTHS[resolution];
        double scale = Math.min(1.0, Math.min(limitWidth / (double) width, limitHeight / (double) height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    int size() { return bytes.size(); }

    void writeTo(DataOutputStream output) throws IOException { bytes.writeTo(output); }

    @Override public void close() {
        writer.dispose();
        if (scaled != null) scaled.flush();
    }
}
