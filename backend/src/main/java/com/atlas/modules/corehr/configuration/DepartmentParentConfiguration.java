package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.port.in.DepartmentParentUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.DepartmentParentService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class DepartmentParentConfiguration {
  private UnitOfWork unit(TransactionTemplate tx) {
    return new UnitOfWork() {
      public <T> T run(Supplier<T> work) {
        return tx.execute(s -> work.get());
      }
    };
  }

  @Bean
  DepartmentParentUseCases departmentParents(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    var reads = new TransactionTemplate(manager);
    reads.setReadOnly(true);
    reads.setTimeout(10);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var writes = new TransactionTemplate(manager);
    writes.setTimeout(10);
    return new DepartmentParentService(
        new JdbcPeopleStore(db),
        new JdbcDepartmentParentStore(db),
        unit(reads),
        unit(writes),
        clock);
  }
}
