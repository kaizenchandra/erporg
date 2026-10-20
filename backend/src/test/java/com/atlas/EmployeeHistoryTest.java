package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.in.web.EmployeeHistoryCursorCodec;
import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmployeeHistoryTest {
  Cursor cursor() {
    return new Cursor(
        9007199254740995L,
        9007199254740993L,
        Instant.parse("2026-10-19T12:00:00.123456Z"),
        "a".repeat(64));
  }

  @Test
  void cursorRoundTripPreservesMicrosecondsAndIdsBeyondJavascriptPrecision() {
    var c = cursor();
    assertEquals(c, EmployeeHistoryCursorCodec.decode(EmployeeHistoryCursorCodec.encode(c)));
    assertNull(EmployeeHistoryCursorCodec.encode(null));
    assertNull(EmployeeHistoryCursorCodec.decode(null));
    assertNull(EmployeeHistoryCursorCodec.decode(""));
    assertTrue(EmployeeHistoryCursorCodec.encode(c).length() <= 256);
  }

  @Test
  void rejectsMalformedVersionTrailingPayloadPaddingAndOversizedCursors() {
    var bytes = Base64.getUrlDecoder().decode(EmployeeHistoryCursorCodec.encode(cursor()));
    var badVersion = bytes.clone();
    badVersion[0] = 2;
    var trailing = Arrays.copyOf(bytes, bytes.length + 1);
    var nanos = bytes.clone();
    nanos[28] = 1; // non-microsecond nanosecond value
    for (var value :
        List.of(
            " ",
            "x",
            "x".repeat(257),
            "a==",
            EmployeeHistoryCursorCodec.encode(cursor()) + "=",
            Base64.getUrlEncoder().withoutPadding().encodeToString(badVersion),
            Base64.getUrlEncoder().withoutPadding().encodeToString(trailing),
            Base64.getUrlEncoder().withoutPadding().encodeToString(nanos)))
      assertThrows(IllegalArgumentException.class, () -> EmployeeHistoryCursorCodec.decode(value));
  }

  @Test
  void validatesScopeFreeQueryAndCursorBounds() {
    for (var q :
        List.of(
            new String[] {"unknown", "ALL", "desc"},
            new String[] {"phone", "UNKNOWN", "desc"},
            new String[] {"ALL", "ALL", "sideways"}))
      assertThrows(IllegalArgumentException.class, () -> new Query(q[0], q[1], q[2], 10, null));
    for (int n : new int[] {0, 51, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> new Query("ALL", "ALL", "desc", n, null));
    assertThrows(
        IllegalArgumentException.class, () -> new Cursor(0, 1, Instant.now(), "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class, () -> new Cursor(1, 2, Instant.now(), "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class, () -> new Cursor(1, 1, Instant.MAX, "a".repeat(64)));
  }
}
