package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.port.in.EmployeeHistoryUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.EmployeeHistoryService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class EmployeeHistoryConfiguration {
  @Bean
  EmployeeHistoryUseCases employeeHistory(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    tx.setTimeout(10);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var reads =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return tx.execute(s -> work.get());
          }
        };
    return new EmployeeHistoryService(
        new JdbcPeopleStore(db), new JdbcEmployeeHistoryStore(db), reads, clock);
  }
}
