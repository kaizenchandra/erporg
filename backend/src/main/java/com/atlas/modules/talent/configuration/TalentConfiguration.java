package com.atlas.modules.talent.configuration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.payroll.api.TalentIntakeAccess;
import com.atlas.modules.talent.adapter.out.integration.CoreHrTalentEnvironment;
import com.atlas.modules.talent.adapter.out.persistence.JdbcTalentStore;
import com.atlas.modules.talent.application.port.in.TalentUseCases;
import com.atlas.modules.talent.application.port.out.TalentPorts.Transactions;
import com.atlas.modules.talent.application.usecase.TalentService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TalentConfiguration {
  @Bean
  TalentUseCases talent(
      JdbcClient db,
      TalentWorkforceAccess w,
      TalentIntakeAccess p,
      PlatformTransactionManager manager,
      Clock clock) {
    var r = new TransactionTemplate(manager);
    r.setReadOnly(true);
    r.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    r.setTimeout(10);
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    return new TalentService(
        new JdbcTalentStore(db),
        new CoreHrTalentEnvironment(w, p),
        boundary(r),
        boundary(t),
        clock);
  }

  private Transactions boundary(TransactionTemplate t) {
    return new Transactions() {
      public <T> T run(Supplier<T> f) {
        return t.execute(s -> f.get());
      }
    };
  }
}
