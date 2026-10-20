package com.atlas.modules.finance.configuration;

import com.atlas.modules.corehr.api.*;
import com.atlas.modules.finance.adapter.in.integration.*;
import com.atlas.modules.finance.adapter.out.integration.CoreHrFinanceReferences;
import com.atlas.modules.finance.adapter.out.persistence.JdbcFinanceStore;
import com.atlas.modules.finance.api.*;
import com.atlas.modules.finance.application.port.in.FinanceUseCases;
import com.atlas.modules.finance.application.port.out.FinancePorts.Transactions;
import com.atlas.modules.finance.application.usecase.FinanceService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class FinanceConfiguration {
  @Bean
  FinanceUseCases finance(
      JdbcClient db,
      PlatformTransactionManager manager,
      WorkforceAccess workforce,
      FinanceReferenceAccess refs,
      Clock clock) {
    var reads = new TransactionTemplate(manager);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setTimeout(10);
    var writes = new TransactionTemplate(manager);
    writes.setTimeout(10);
    return new FinanceService(
        new JdbcFinanceStore(db),
        new CoreHrFinanceReferences(workforce, refs),
        boundary(reads),
        boundary(writes),
        clock);
  }

  @Bean
  PayrollFinanceAccess payrollFinanceAccess(FinanceUseCases finance) {
    return new PayrollFinanceAdapter(finance);
  }

  @Bean
  PayrollJournalAccess payrollJournals(FinanceUseCases finance) {
    return new PayrollJournalAdapter(finance);
  }

  private Transactions boundary(TransactionTemplate t) {
    return new Transactions() {
      public <T> T run(Supplier<T> work) {
        return t.execute(s -> work.get());
      }
    };
  }
}
