package com.atlas.modules.documents.adapter.out.scanning;

import com.atlas.modules.documents.application.model.ScanResult;
import com.atlas.modules.documents.application.port.out.MalwareScanner;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class ClamAvScanner implements MalwareScanner {
  private final String host;
  private final int port;

  public ClamAvScanner(String host, int port) {
    this.host = host;
    this.port = port;
  }

  private Socket socket() throws IOException {
    var socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), 2000);
      socket.setSoTimeout(3000);
      return socket;
    } catch (IOException e) {
      socket.close();
      throw e;
    }
  }

  private String response(InputStream input) throws IOException {
    var result = new ByteArrayOutputStream();
    for (int i = 0; i < 256; i++) {
      int b = input.read();
      if (b < 0 || b == 0 || b == '\n') return result.toString(StandardCharsets.US_ASCII);
      result.write(b);
    }
    throw new IOException();
  }

  public ScanResult scan(byte[] content) {
    try {
      String version;
      try (var s = socket()) {
        s.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
        version = response(s.getInputStream());
      }
      if (!version.startsWith("ClamAV ") || !version.matches("[a-zA-Z0-9 /:.-]{1,160}"))
        throw new IOException();
      try (var s = socket()) {
        var output = new DataOutputStream(s.getOutputStream());
        output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
        output.writeInt(content.length);
        output.write(content);
        output.writeInt(0);
        output.flush();
        String result = response(s.getInputStream());
        if (result.equals("stream: OK")) return new ScanResult("CLEAN", version);
        if (result.startsWith("stream: ") && result.endsWith(" FOUND"))
          return new ScanResult("INFECTED", version);
        throw new IOException();
      }
    } catch (IOException e) {
      return new ScanResult("UNAVAILABLE", "ClamAV unavailable or scan failed");
    }
  }
}
