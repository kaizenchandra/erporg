package com.atlas.modules.payroll.configuration;

import com.atlas.modules.corehr.api.*;
import com.atlas.modules.finance.api.*;
import com.atlas.modules.payroll.adapter.out.integration.LocalPayrollEnvironment;
import com.atlas.modules.payroll.adapter.out.persistence.JdbcPayrollStore;
import com.atlas.modules.payroll.application.port.in.PayrollUseCases;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Transactions;
import com.atlas.modules.payroll.application.usecase.PayrollService;
import com.atlas.modules.timekeeping.api.ApprovedTimeAccess;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class PayrollConfiguration {
  @Bean
  PayrollUseCases payroll(
      JdbcClient db,
      PlatformTransactionManager manager,
      WorkforceAccess w,
      FinanceReferenceAccess r,
      PayrollFinanceAccess f,
      ApprovedTimeAccess t,
      PayrollJournalAccess j,
      Clock clock) {
    var read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setTimeout(10);
    var write = new TransactionTemplate(manager);
    write.setTimeout(10);
    return new PayrollService(
        new JdbcPayrollStore(db),
        new LocalPayrollEnvironment(w, r, f, t, j),
        boundary(read),
        boundary(write),
        clock);
  }

  private Transactions boundary(TransactionTemplate t) {
    return new Transactions() {
      public <T> T run(Supplier<T> work) {
        return t.execute(s -> work.get());
      }
    };
  }
}
