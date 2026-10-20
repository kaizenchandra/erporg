package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore;
import com.atlas.modules.corehr.application.port.in.LegalEntitiesUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.LegalEntitiesService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class LegalEntitiesConfiguration {
  private UnitOfWork boundary(PlatformTransactionManager manager, boolean read) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    t.setReadOnly(read);
    if (read) t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return new UnitOfWork() {
      public <T> T run(Supplier<T> work) {
        return t.execute(s -> work.get());
      }
    };
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.FinancialReferenceUseCases financialReferenceQueries(
      JdbcClient db,
      PlatformTransactionManager manager,
      com.atlas.modules.corehr.application.port.in.PeopleUseCases people) {
    return new com.atlas.modules.corehr.application.usecase.FinancialReferenceService(
        people,
        new JdbcLegalEntityStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(db),
        boundary(manager, true));
  }

  @Bean
  com.atlas.modules.corehr.api.FinanceReferenceAccess financeReferences(
      com.atlas.modules.corehr.application.port.in.FinancialReferenceUseCases queries) {
    return new com.atlas.modules.corehr.adapter.in.integration.FinanceReferencesAdapter(queries);
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.EntityAssignmentUseCases entityAssignment(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new com.atlas.modules.corehr.application.usecase.EntityAssignmentService(
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(db),
        boundary(manager, true),
        clock);
  }

  @Bean
  LegalEntitiesUseCases legalEntities(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new LegalEntitiesService(
        new JdbcLegalEntityStore(db), boundary(manager, false), boundary(manager, true), clock);
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.LegalEntityRetirementUseCases legalEntityRetirement(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new com.atlas.modules.corehr.application.usecase.LegalEntityRetirementService(
        new JdbcLegalEntityStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(db),
        boundary(manager, false),
        boundary(manager, true),
        clock);
  }
}
