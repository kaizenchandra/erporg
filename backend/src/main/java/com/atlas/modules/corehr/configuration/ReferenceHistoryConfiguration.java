package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcReferenceHistoryStore;
import com.atlas.modules.corehr.application.port.in.ReferenceHistoryUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.ReferenceHistoryService;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ReferenceHistoryConfiguration {
  @Bean
  ReferenceHistoryUseCases referenceHistory(JdbcClient db, PlatformTransactionManager manager) {
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    tx.setTimeout(10);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var reads =
        new UnitOfWork() {
          public <T> T run(Supplier<T> s) {
            return tx.execute(t -> s.get());
          }
        };
    return new ReferenceHistoryService(new JdbcReferenceHistoryStore(db), reads);
  }
}
