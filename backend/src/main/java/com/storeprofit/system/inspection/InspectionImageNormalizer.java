package com.storeprofit.system.inspection;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class InspectionImageNormalizer {
  private static final int CANVAS_WIDTH = 1600;
  private static final int CANVAS_HEIGHT = 1200;

  public byte[] normalize(byte[] source) throws Exception {
    if (source == null || source.length == 0) {
      throw new IllegalArgumentException("empty image");
    }
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
    if (decoded == null) {
      throw new IllegalArgumentException("unsupported or damaged image");
    }
    BufferedImage oriented = orient(decoded, orientation(source));
    BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = canvas.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      double scale = Math.min((double) CANVAS_WIDTH / oriented.getWidth(),
          (double) CANVAS_HEIGHT / oriented.getHeight());
      int width = Math.max(1, (int) Math.round(oriented.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(oriented.getHeight() * scale));
      int x = (CANVAS_WIDTH - width) / 2;
      int y = (CANVAS_HEIGHT - height) / 2;
      graphics.drawImage(oriented, x, y, width, height, null);
    } finally {
      graphics.dispose();
    }
    return jpeg(canvas);
  }

  private int orientation(byte[] source) {
    try {
      Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
      ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      return directory == null ? 1 : directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
    } catch (Exception ignored) {
      return 1;
    }
  }

  BufferedImage orient(BufferedImage source, int orientation) {
    int sourceWidth = source.getWidth();
    int sourceHeight = source.getHeight();
    boolean swapDimensions = orientation >= 5 && orientation <= 8;
    BufferedImage target = new BufferedImage(
        swapDimensions ? sourceHeight : sourceWidth,
        swapDimensions ? sourceWidth : sourceHeight,
        BufferedImage.TYPE_INT_ARGB
    );
    for (int y = 0; y < sourceHeight; y++) {
      for (int x = 0; x < sourceWidth; x++) {
        int targetX;
        int targetY;
        switch (orientation) {
          case 2 -> { targetX = sourceWidth - 1 - x; targetY = y; }
          case 3 -> { targetX = sourceWidth - 1 - x; targetY = sourceHeight - 1 - y; }
          case 4 -> { targetX = x; targetY = sourceHeight - 1 - y; }
          case 5 -> { targetX = y; targetY = x; }
          case 6 -> { targetX = sourceHeight - 1 - y; targetY = x; }
          case 7 -> { targetX = sourceHeight - 1 - y; targetY = sourceWidth - 1 - x; }
          case 8 -> { targetX = y; targetY = sourceWidth - 1 - x; }
          default -> { targetX = x; targetY = y; }
        }
        target.setRGB(targetX, targetY, source.getRGB(x, y));
      }
    }
    return target;
  }

  private byte[] jpeg(BufferedImage image) throws Exception {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IllegalStateException("JPEG encoder unavailable");
    }
    ImageWriter writer = writers.next();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
         ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(imageOutput);
      ImageWriteParam params = writer.getDefaultWriteParam();
      if (params.canWriteCompressed()) {
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.85f);
      }
      writer.write(null, new IIOImage(image, null, null), params);
      imageOutput.flush();
      return output.toByteArray();
    } finally {
      writer.dispose();
    }
  }
}
