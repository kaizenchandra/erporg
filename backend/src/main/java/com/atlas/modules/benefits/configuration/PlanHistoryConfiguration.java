package com.atlas.modules.benefits.configuration;

import com.atlas.modules.benefits.adapter.out.persistence.JdbcPlanHistoryStore;
import com.atlas.modules.benefits.application.port.in.PlanHistoryUseCases;
import com.atlas.modules.benefits.application.port.out.UnitOfWork;
import com.atlas.modules.benefits.application.usecase.PlanHistoryService;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class PlanHistoryConfiguration {
  @Bean
  PlanHistoryUseCases planHistory(
      JdbcClient db,
      PlatformTransactionManager manager,
      com.atlas.modules.corehr.api.WorkforceAccess access) {
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
    return new PlanHistoryService(
        new JdbcPlanHistoryStore(db),
        new com.atlas.modules.benefits.adapter.out.integration.CoreHrWorkforceAdapter(access),
        reads);
  }
}
