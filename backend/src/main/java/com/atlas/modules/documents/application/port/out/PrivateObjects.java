package com.atlas.modules.documents.application.port.out;

public interface PrivateObjects {
  void put(String key, byte[] content);

  byte[] read(String key);
}
