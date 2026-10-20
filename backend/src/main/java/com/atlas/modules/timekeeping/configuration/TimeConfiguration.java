package com.atlas.modules.timekeeping.configuration;

import com.atlas.modules.corehr.api.TimeWorkforceAccess;
import com.atlas.modules.timekeeping.adapter.out.integration.CoreHrTimeWorkforce;
import com.atlas.modules.timekeeping.adapter.out.persistence.JdbcTimeStore;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;
import com.atlas.modules.timekeeping.application.port.out.UnitOfWork;
import com.atlas.modules.timekeeping.application.usecase.TimeService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TimeConfiguration {
  @Bean
  com.atlas.modules.timekeeping.api.ApprovedTimeAccess approvedTime(TimeUseCases time) {
    return new com.atlas.modules.timekeeping.adapter.in.integration.ApprovedTimeAdapter(time);
  }

  @Bean
  TimeUseCases timeUseCases(
      JdbcClient db, PlatformTransactionManager manager, TimeWorkforceAccess access) {
    var reads = new TransactionTemplate(manager);
    reads.setReadOnly(true);
    reads.setTimeout(10);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var writes = new TransactionTemplate(manager);
    writes.setTimeout(10);
    return new TimeService(
        new JdbcTimeStore(db),
        new CoreHrTimeWorkforce(access),
        boundary(reads),
        boundary(writes),
        Clock.systemUTC());
  }

  private UnitOfWork boundary(TransactionTemplate tx) {
    return new UnitOfWork() {
      public <T> T run(Supplier<T> operation) {
        return tx.execute(status -> operation.get());
      }
    };
  }
}
