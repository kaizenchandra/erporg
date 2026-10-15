package com.atlas.modules.documents.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {
  <T> T run(Supplier<T> operation);
}
