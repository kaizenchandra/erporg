package com.atlas.modules.benefits.application.port.out;

public interface UnitOfWork {
  <T> T run(java.util.function.Supplier<T> operation);
}
