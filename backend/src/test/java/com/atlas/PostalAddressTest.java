package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PostalAddressTest {
  @Test
  void canonicalUnicodeOptionalAndNormalisationRoundTrip() {
    var a = new PostalAddress("  18 Rue Étoile 李  ", null, " Montréal ", "Québec", "H2X 1Y4", "ca");
    a.requireCurrentCountry();
    assertEquals("18 Rue Étoile 李", a.line1());
    assertEquals("", a.line2());
    assertEquals(a, PostalAddress.decode(a.canonicalValue()).orElseThrow());
    assertTrue(PostalAddress.decode("Existing free-text address").isEmpty());
  }

  @Test
  void rejectsControlTextOverflowUnknownFormatAndNonCanonicalValues() {
    assertThrows(
        ChangeRequest.Validation.class, () -> new PostalAddress("a\nb", "", "City", "", "", "US"));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new PostalAddress("x".repeat(121), "", "City", "", "", "US"));
    assertThrows(
        ChangeRequest.Validation.class, () -> new PostalAddress("x", "", " ", "", "", "US"));
    assertThrows(ChangeRequest.Validation.class, () -> PostalAddress.decode("@POSTAL/2\na"));
    assertThrows(
        ChangeRequest.Validation.class, () -> PostalAddress.decode("@POSTAL/1\nx\n\nCity\n\n\nus"));
    assertThrows(
        ChangeRequest.Validation.class, () -> PostalAddress.decode("@POSTAL/1\nx\n\nCity\n\nUS"));
  }

  @Test
  void rejectsWhitespacePrefixedCanonicalInputAndCountryControlCharacters() {
    var a = new PostalAddress("Street", "", "City", "", "", "US");
    assertTrue(PostalAddress.decode(" " + a.canonicalValue()).isEmpty());
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new PostalAddress("Street", "", "City", "", "", "US\n"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            ChangeRequest.validateShape(
                "address", " " + a.canonicalValue(), "Supplied address", LocalDate.now()));
  }

  @Test
  void historicalCountryDecodingDoesNotDependOnCurrentCatalogue() {
    var a = new PostalAddress("Historical address", "", "City", "", "", "ZZ");
    assertEquals(a, PostalAddress.decode(a.canonicalValue()).orElseThrow());
    assertThrows(ChangeRequest.Validation.class, a::requireCurrentCountry);
  }

  @Test
  void widerCanonicalAddressDoesNotWidenOtherOrLegacyFields() {
    var a =
        new PostalAddress(
            "x".repeat(120), "y".repeat(120), "c".repeat(80), "r".repeat(80), "p".repeat(30), "GB");
    assertTrue(a.canonicalValue().length() > 120);
    assertDoesNotThrow(
        () ->
            ChangeRequest.validateShape(
                "address", a.canonicalValue(), "Supplied by employee", LocalDate.now()));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> ChangeRequest.validateShape("address", "a".repeat(121), "Reason", LocalDate.now()));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> ChangeRequest.validateShape("name", "a".repeat(121), "Reason", LocalDate.now()));
  }
}
