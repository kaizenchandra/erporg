package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import java.util.Map;

/** HTTP-only numeric conversion shared by the three legacy Long revision inputs. */
final class CoreHrCommandVersion {
  private CoreHrCommandVersion() {}

  static long exact(BigDecimal version) {
    try {
      if (version == null || version.signum() < 0) throw new ArithmeticException();
      return version.longValueExact();
    } catch (ArithmeticException e) {
      throw new InputValidation(
          Map.of("version", "An exact nonnegative employee version is required."));
    }
  }
}
