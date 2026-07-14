package com.storeprofit.system.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

final class InMemoryMultipartFile implements MultipartFile {
  private final String name;
  private final String originalFilename;
  private final String contentType;
  private final byte[] bytes;

  InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
    this.name = name;
    this.originalFilename = originalFilename;
    this.contentType = contentType;
    this.bytes = bytes.clone();
  }

  @Override public String getName() { return name; }
  @Override public String getOriginalFilename() { return originalFilename; }
  @Override public String getContentType() { return contentType; }
  @Override public boolean isEmpty() { return bytes.length == 0; }
  @Override public long getSize() { return bytes.length; }
  @Override public byte[] getBytes() { return bytes.clone(); }
  @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
  @Override public void transferTo(java.io.File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), bytes); }
}
