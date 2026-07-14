package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
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
}
