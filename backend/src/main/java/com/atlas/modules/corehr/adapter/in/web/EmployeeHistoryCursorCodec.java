package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.Cursor;
import java.io.*;
import java.time.*;
import java.util.Base64;

/** Untrusted navigation state; current backend scope is enforced on every page. */
public final class EmployeeHistoryCursorCodec {
  private EmployeeHistoryCursorCodec() {}

  public static String encode(Cursor c) {
    if (c == null) return null;
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeByte(1);
      out.writeLong(c.throughId());
      out.writeLong(c.beforeId());
      out.writeLong(c.beforeRecordedAt().getEpochSecond());
      out.writeInt(c.beforeRecordedAt().getNano());
      out.write(c.fingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot encode history cursor", e);
    }
  }

  public static Cursor decode(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      if (value.length() > 256 || !value.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      var in = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(value)));
      if (in.readUnsignedByte() != 1) throw new IllegalArgumentException();
      long through = in.readLong(), id = in.readLong(), seconds = in.readLong();
      int nanos = in.readInt();
      if (nanos < 0 || nanos > 999999999 || nanos % 1000 != 0) throw new IllegalArgumentException();
      var fingerprint = in.readNBytes(64);
      if (fingerprint.length != 64) throw new IllegalArgumentException();
      var cursor =
          new Cursor(
              through,
              id,
              Instant.ofEpochSecond(seconds, nanos),
              new String(fingerprint, java.nio.charset.StandardCharsets.US_ASCII));
      if (in.available() != 0 || !value.equals(encode(cursor)))
        throw new IllegalArgumentException();
      return cursor;
    } catch (IOException | DateTimeException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid history cursor. Refresh history.");
    }
  }
}
