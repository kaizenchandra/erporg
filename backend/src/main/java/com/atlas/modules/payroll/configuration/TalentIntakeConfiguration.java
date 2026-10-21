package com.atlas.modules.payroll.configuration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.payroll.adapter.in.integration.TalentIntakeAdapter;
import com.atlas.modules.payroll.adapter.out.integration.TalentIdentityAdapter;
import com.atlas.modules.payroll.adapter.out.persistence.JdbcTalentIntakeStore;
import com.atlas.modules.payroll.api.TalentIntakeAccess;
import com.atlas.modules.payroll.application.port.in.TalentIntakeUseCases;
import com.atlas.modules.payroll.application.port.out.PayrollPorts.Transactions;
import com.atlas.modules.payroll.application.usecase.TalentIntakeService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TalentIntakeConfiguration {
  @Bean
  TalentIntakeUseCases talentIntake(
      JdbcClient db,
      TalentWorkforceAccess w,
      PlatformTransactionManager manager,
      Clock clock,
      com.atlas.modules.payroll.application.port.in.CompensationUseCases compensation) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    return new TalentIntakeService(
        new JdbcTalentIntakeStore(db),
        new TalentIdentityAdapter(w),
        new Transactions() {
          public <T> T run(Supplier<T> f) {
            return t.execute(s -> f.get());
          }
        },
        clock,
        compensation);
  }

  @Bean
  TalentIntakeAccess talentIntakeAccess(TalentIntakeUseCases p) {
    return new TalentIntakeAdapter(p);
  }
}
