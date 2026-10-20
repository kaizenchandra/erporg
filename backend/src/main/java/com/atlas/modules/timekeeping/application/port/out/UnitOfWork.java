package com.atlas.modules.timekeeping.application.port.out;

public interface UnitOfWork {
  <T> T run(java.util.function.Supplier<T> operation);
}
