package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcOrganisationStore;
import com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore;
import com.atlas.modules.corehr.application.port.in.OrganisationUseCases;
import com.atlas.modules.corehr.application.port.in.PeopleUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.OrganisationService;
import com.atlas.modules.corehr.application.usecase.PeopleService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class CoreHrConfiguration {
  @Bean
  com.atlas.modules.corehr.application.port.in.HiringUseCases hiring(
      JdbcClient jdbc, PlatformTransactionManager manager, OrganisationUseCases organisation) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new com.atlas.modules.corehr.application.usecase.HiringService(
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcHiringStore(jdbc),
        new JdbcPeopleStore(jdbc),
        organisation,
        tx,
        Clock.systemUTC());
  }

  @Bean
  OrganisationUseCases organisation(JdbcClient jdbc, PlatformTransactionManager manager) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new OrganisationService(new JdbcOrganisationStore(jdbc), tx, Clock.systemUTC());
  }

  @Bean
  PeopleUseCases people(
      JdbcClient jdbc, PlatformTransactionManager manager, OrganisationUseCases organisation) {
    var tx = new TransactionTemplate(manager);
    tx.setTimeout(10);
    UnitOfWork uow =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return tx.execute(status -> operation.get());
          }
        };
    return new PeopleService(new JdbcPeopleStore(jdbc), uow, Clock.systemUTC(), organisation);
  }
}
