package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WarehousePdfRendererTest {
  private final WarehousePdfRenderer renderer = new WarehousePdfRenderer();

  @Test
  void rendersReceivingWarehouseSnapshotIntoPdfHeaderAndIgnoresReceiveDepartment() throws IOException {
    BufferedImage snapshotImage = renderedImage(renderer.returnOrder(returnOrder(
        "\\u8346\\u5dde\\u603b\\u4ed3", "\\u4f2a\\u9020\\u9875\\u9762\\u6536\\u8d27\\u90e8\\u95e8")));
    BufferedImage forgedDepartmentImage = renderedImage(renderer.returnOrder(returnOrder(
        "\\u8346\\u5dde\\u603b\\u4ed3", "\\u4ed3\\u5e93")));
    BufferedImage differentWarehouseImage = renderedImage(renderer.returnOrder(returnOrder(
        "\\u8346\\u5dde\\u4e8c\\u4ed3", "\\u4f2a\\u9020\\u9875\\u9762\\u6536\\u8d27\\u90e8\\u95e8")));

    int[] snapshotHeader = receivingWarehouseHeader(snapshotImage);
    assertThat(snapshotImage.getWidth()).isEqualTo(1240);
    assertThat(snapshotImage.getHeight()).isEqualTo(1754);
    assertThat(snapshotHeader).containsAnyOf(0xff000000);
    assertThat(receivingWarehouseHeader(forgedDepartmentImage)).containsExactly(snapshotHeader);
    assertThat(receivingWarehouseHeader(differentWarehouseImage)).isNotEqualTo(snapshotHeader);
  }

  private WarehouseReturnResponse returnOrder(String receiveWarehouseName, String receiveDepartment) {
    WarehouseReturnLineResponse line = new WarehouseReturnLineResponse(
        1L, 1L, "Milk", "12-box", 1L, "B001", 1L,
        new BigDecimal("2"), "case", new BigDecimal("6.00"),
        new BigDecimal("6.00"), new BigDecimal("12.00"), "return", ""
    );
    return new WarehouseReturnResponse(
        "return-pdf-test", "PSTH-PDF-TEST", "REQ-1", "DEL-1",
        "store-1", "Return Store", 1L, receiveWarehouseName, receiveDepartment,
        "RECEIVED", "received", new BigDecimal("12.00"), "handler", "creator", "updater",
        null, null, "return", "", null, null, "2026-07-15", null, null,
        "2026-07-15 10:00:00", "2026-07-15 10:00:00", 1, 0, List.of(line)
    );
  }

  private BufferedImage renderedImage(byte[] pdf) throws IOException {
    byte[] imageMarker = "/Subtype /Image".getBytes(StandardCharsets.US_ASCII);
    byte[] lengthMarker = "/Length ".getBytes(StandardCharsets.US_ASCII);
    byte[] streamMarker = " >>\nstream\n".getBytes(StandardCharsets.US_ASCII);
    int imageObject = indexOf(pdf, imageMarker, 0);
    int lengthStart = indexOf(pdf, lengthMarker, imageObject) + lengthMarker.length;
    int streamStart = indexOf(pdf, streamMarker, lengthStart);
    int length = Integer.parseInt(new String(pdf, lengthStart, streamStart - lengthStart, StandardCharsets.US_ASCII));
    return ImageIO.read(new ByteArrayInputStream(pdf, streamStart + streamMarker.length, length));
  }

  private int[] receivingWarehouseHeader(BufferedImage image) {
    return image.getRGB(360, 108, 400, 54, null, 0, 400);
  }

  private int indexOf(byte[] source, byte[] target, int offset) {
    outer:
    for (int start = offset; start <= source.length - target.length; start++) {
      for (int index = 0; index < target.length; index++) {
        if (source[start + index] != target[index]) {
          continue outer;
        }
      }
      return start;
    }
    throw new AssertionError("PDF image stream was not found");
  }
}
