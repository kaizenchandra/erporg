package com.atlas.modules.benefits.configuration;

import com.atlas.modules.benefits.adapter.out.integration.CoreHrWorkforceAdapter;
import com.atlas.modules.benefits.adapter.out.persistence.JdbcPlanStore;
import com.atlas.modules.benefits.application.port.in.BenefitsUseCases;
import com.atlas.modules.benefits.application.port.out.UnitOfWork;
import com.atlas.modules.benefits.application.usecase.BenefitsService;
import com.atlas.modules.corehr.api.WorkforceAccess;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class BenefitsConfiguration {
  @Bean
  BenefitsUseCases benefits(
      JdbcClient jdbc, PlatformTransactionManager manager, WorkforceAccess access) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new BenefitsService(
        new JdbcPlanStore(jdbc), new CoreHrWorkforceAdapter(access), tx, Clock.systemUTC());
  }

  @Bean
  com.atlas.modules.benefits.application.port.in.SelectionsUseCases selections(
      JdbcClient jdbc, PlatformTransactionManager manager, WorkforceAccess access) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new com.atlas.modules.benefits.application.usecase.SelectionsService(
        new com.atlas.modules.benefits.adapter.out.persistence.JdbcSelectionStore(jdbc),
        new CoreHrWorkforceAdapter(access),
        tx,
        Clock.systemUTC());
  }
}
