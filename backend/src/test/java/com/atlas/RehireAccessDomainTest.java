package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RehireAccessDomainTest {
  @Test
  void revocationProtectsVersionTransitionReasonAndSelfAction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RehireAccessRevocation(
                "grant",
                "E003",
                "sophia",
                2,
                4,
                "amelia",
                "E001",
                "reason",
                Instant.now(),
                "correlation"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            new RehireAccessRevocation(
                "grant",
                "E003",
                "sophia",
                2,
                3,
                "alias",
                "E003",
                "reason",
                Instant.now(),
                "correlation"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new RehireAccessRevocation(
                "grant",
                "E003",
                "sophia",
                2,
                3,
                "amelia",
                "E001",
                " ",
                Instant.now(),
                "correlation"));
  }
}
