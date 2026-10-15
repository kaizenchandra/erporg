package com.atlas.modules.documents.adapter.out.storage;

import com.atlas.modules.documents.application.port.out.PrivateObjects;
import com.atlas.shared.domain.CommandConflict;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

public final class LocalPrivateObjects implements PrivateObjects {
  private final Path root;

  public LocalPrivateObjects(Path root) {
    this.root = root.toAbsolutePath().normalize();
    try {
      Files.createDirectories(
          this.root,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      if (Files.isSymbolicLink(this.root) || !this.root.toRealPath().equals(this.root))
        throw new IOException();
      Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------"));
    } catch (IOException e) {
      throw new IllegalStateException("Private local storage unavailable.");
    }
  }

  private Path path(String key) throws IOException {
    if (!key.matches("[a-f0-9]{64}/[a-f0-9]{64}")) throw new IOException();
    var path = root.resolve(key);
    var directory = path.getParent();
    Files.createDirectories(
        directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(directory)
        || !directory.toRealPath().equals(directory)
        || Files.isSymbolicLink(path)) throw new IOException();
    return path;
  }

  public synchronized void put(String key, byte[] content) {
    try {
      Path path = path(key);
      try (var output =
          Files.newOutputStream(
              path,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        output.write(content);
      } catch (FileAlreadyExistsException e) {
        if (!Arrays.equals(read(key), content)) throw new IOException();
      }
    } catch (IOException e) {
      throw new CommandConflict(
          "Private document storage is unavailable; retry or contact HR operations.");
    }
  }

  public synchronized byte[] read(String key) {
    try {
      var path = path(key);
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        var bytes = input.readNBytes(16385);
        if (bytes.length > 16384) throw new IOException();
        return bytes;
      }
    } catch (IOException e) {
      throw new CommandConflict("Private document content is unavailable; contact HR operations.");
    }
  }
}
