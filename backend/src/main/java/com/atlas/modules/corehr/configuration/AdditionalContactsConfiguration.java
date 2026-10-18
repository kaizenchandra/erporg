package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.port.in.AdditionalContactsUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.AdditionalContactsService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AdditionalContactsConfiguration {
  @Bean
  AdditionalContactsUseCases additionalContacts(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    var writes = new TransactionTemplate(manager);
    writes.setTimeout(10);
    var reads = new TransactionTemplate(manager);
    reads.setTimeout(10);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return new AdditionalContactsService(
        new JdbcAdditionalContactsStore(db),
        new JdbcPeopleStore(db),
        unit(writes),
        unit(reads),
        clock);
  }

  private UnitOfWork unit(TransactionTemplate template) {
    return new UnitOfWork() {
      public <T> T run(Supplier<T> operation) {
        return template.execute(status -> operation.get());
      }
    };
  }
}
