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
  private static final int MAX_LONG_EDGE = 1600;

  public byte[] normalize(byte[] source) throws Exception {
    return normalize(source, CANVAS_WIDTH, CANVAS_HEIGHT);
  }

  /**
   * Produces an export-only JPEG canvas whose aspect ratio matches the supplied photo frame.
   * The source image is never changed. The canvas uses a white background so that fitting it
   * into an Excel picture anchor cannot crop or stretch the inspection evidence.
   */
  public byte[] normalize(byte[] source, int frameWidth, int frameHeight) throws Exception {
    if (source == null || source.length == 0) {
      throw new IllegalArgumentException("empty image");
    }
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
    if (decoded == null) {
      throw new IllegalArgumentException("unsupported or damaged image");
    }
    BufferedImage oriented = orient(decoded, orientation(source));
    int[] canvasSize = canvasSize(frameWidth, frameHeight);
    int canvasWidth = canvasSize[0];
    int canvasHeight = canvasSize[1];
    BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = canvas.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, canvasWidth, canvasHeight);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      double scale = Math.min((double) canvasWidth / oriented.getWidth(),
          (double) canvasHeight / oriented.getHeight());
      int width = Math.max(1, (int) Math.round(oriented.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(oriented.getHeight() * scale));
      int x = (canvasWidth - width) / 2;
      int y = (canvasHeight - height) / 2;
      graphics.drawImage(oriented, x, y, width, height, null);
    } finally {
      graphics.dispose();
    }
    return jpeg(canvas);
  }

  private int[] canvasSize(int frameWidth, int frameHeight) {
    if (frameWidth <= 0 || frameHeight <= 0) {
      return new int[] {CANVAS_WIDTH, CANVAS_HEIGHT};
    }
    if (frameWidth >= frameHeight) {
      return new int[] {
          MAX_LONG_EDGE,
          Math.max(1, (int) Math.round((double) MAX_LONG_EDGE * frameHeight / frameWidth))
      };
    }
    return new int[] {
        Math.max(1, (int) Math.round((double) MAX_LONG_EDGE * frameWidth / frameHeight)),
        MAX_LONG_EDGE
    };
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
