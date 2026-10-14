package com.atlas.modules.corehr.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {
  <T> T run(Supplier<T> operation);
}
