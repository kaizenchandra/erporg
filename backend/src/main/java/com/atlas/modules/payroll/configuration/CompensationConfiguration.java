package com.atlas.modules.payroll.configuration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.finance.api.PayrollFinanceAccess;
import com.atlas.modules.payroll.adapter.out.integration.CompensationEnvironment;
import com.atlas.modules.payroll.adapter.out.persistence.JdbcCompensationStore;
import com.atlas.modules.payroll.api.CompensationAccess;
import com.atlas.modules.payroll.application.port.in.CompensationUseCases;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Transactions;
import com.atlas.modules.payroll.application.usecase.CompensationService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class CompensationConfiguration {
  @Bean
  CompensationUseCases compensation(
      JdbcClient db,
      TalentWorkforceAccess w,
      PayrollFinanceAccess f,
      PlatformTransactionManager manager,
      Clock clock) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    return new CompensationService(
        new JdbcCompensationStore(db),
        new CompensationEnvironment(w, f),
        new Transactions() {
          public <T> T run(Supplier<T> work) {
            return t.execute(s -> work.get());
          }
        },
        clock);
  }

  @Bean
  @Primary
  CompensationAccess compensationAccess(CompensationUseCases u) {
    return (s, e, d) -> u.salary(s, e, d);
  }
}
