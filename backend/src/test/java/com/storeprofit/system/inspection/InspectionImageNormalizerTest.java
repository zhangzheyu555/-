package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InspectionImageNormalizerTest {
  private final InspectionImageNormalizer normalizer = new InspectionImageNormalizer();

  @Test
  void appliesAllExifMirrorAndRotationOrientations() {
    BufferedImage source = source();

    assertThat(rgb(normalizer.orient(source, 2), 1, 0)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 3), 1, 2)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 4), 0, 2)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 5), 0, 0)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 6), 2, 0)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 7), 2, 1)).isEqualTo(Color.RED.getRGB());
    assertThat(rgb(normalizer.orient(source, 8), 0, 1)).isEqualTo(Color.RED.getRGB());
  }

  @Test
  void usesTheExcelPhotoFrameAspectWithoutCroppingOrStretching() throws Exception {
    BufferedImage source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < source.getHeight(); y++) {
      for (int x = 0; x < source.getWidth(); x++) {
        source.setRGB(x, y, Color.RED.getRGB());
      }
    }

    BufferedImage normalized = ImageIO.read(new java.io.ByteArrayInputStream(normalizer.normalize(png(source), 2, 3)));

    assertThat(normalized.getWidth()).isEqualTo(1067);
    assertThat(normalized.getHeight()).isEqualTo(1600);
    assertThat(Math.max(normalized.getWidth(), normalized.getHeight())).isLessThanOrEqualTo(1600);
    assertWhite(colorAt(normalized, normalized.getWidth() / 2, 20));
    assertRed(colorAt(normalized, normalized.getWidth() / 2, normalized.getHeight() / 2));
    assertWhite(colorAt(normalized, normalized.getWidth() / 2, normalized.getHeight() - 20));
  }

  @Test
  void retainsTheDefaultCanvasForExistingCallersAndFallsBackForInvalidFrames() throws Exception {
    byte[] source = png(source());

    BufferedImage defaultCanvas = ImageIO.read(new java.io.ByteArrayInputStream(normalizer.normalize(source)));
    BufferedImage fallbackCanvas = ImageIO.read(new java.io.ByteArrayInputStream(normalizer.normalize(source, 0, 20)));
    BufferedImage landscapeCanvas = ImageIO.read(new java.io.ByteArrayInputStream(normalizer.normalize(source, 4, 2)));

    assertThat(defaultCanvas.getWidth()).isEqualTo(1600);
    assertThat(defaultCanvas.getHeight()).isEqualTo(1200);
    assertThat(fallbackCanvas.getWidth()).isEqualTo(1600);
    assertThat(fallbackCanvas.getHeight()).isEqualTo(1200);
    assertThat(landscapeCanvas.getWidth()).isEqualTo(1600);
    assertThat(landscapeCanvas.getHeight()).isEqualTo(800);
  }

  private BufferedImage source() {
    BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, Color.RED.getRGB());
    image.setRGB(1, 0, Color.GREEN.getRGB());
    image.setRGB(0, 1, Color.BLUE.getRGB());
    image.setRGB(1, 1, Color.YELLOW.getRGB());
    image.setRGB(0, 2, Color.CYAN.getRGB());
    image.setRGB(1, 2, Color.MAGENTA.getRGB());
    return image;
  }

  private int rgb(BufferedImage image, int x, int y) {
    return image.getRGB(x, y);
  }

  private byte[] png(BufferedImage image) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private Color colorAt(BufferedImage image, int x, int y) {
    return new Color(image.getRGB(x, y));
  }

  private void assertWhite(Color color) {
    assertThat(color.getRed()).isGreaterThan(247);
    assertThat(color.getGreen()).isGreaterThan(247);
    assertThat(color.getBlue()).isGreaterThan(247);
  }

  private void assertRed(Color color) {
    assertThat(color.getRed()).isGreaterThan(220);
    assertThat(color.getGreen()).isLessThan(30);
    assertThat(color.getBlue()).isLessThan(30);
  }
}
