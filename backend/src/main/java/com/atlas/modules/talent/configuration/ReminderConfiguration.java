package com.atlas.modules.talent.configuration;

import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.payroll.api.*;
import com.atlas.modules.talent.adapter.out.integration.CoreHrTalentEnvironment;
import com.atlas.modules.talent.adapter.out.persistence.JdbcTalentStore;
import com.atlas.modules.talent.application.port.in.ReminderUseCases;
import com.atlas.modules.talent.application.port.out.TalentPorts.Transactions;
import com.atlas.modules.talent.application.usecase.ReminderWorkflows;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ReminderConfiguration {
  @Bean
  ReminderUseCases talentReminders(
      JdbcClient db,
      TalentWorkforceAccess w,
      TalentIntakeAccess p,
      CompensationAccess compensation,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${atlas.talent.reminders.scheduler-enabled:false}") boolean enabled) {
    var r = new TransactionTemplate(manager);
    r.setReadOnly(true);
    r.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    r.setTimeout(10);
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    return new ReminderWorkflows(
        new JdbcTalentStore(db),
        new CoreHrTalentEnvironment(w, p, compensation),
        boundary(r),
        boundary(t),
        clock,
        enabled);
  }

  private Transactions boundary(TransactionTemplate tx) {
    return new Transactions() {
      public <T> T run(Supplier<T> f) {
        return tx.execute(s -> f.get());
      }
    };
  }

  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "atlas.talent.reminders",
      name = "scheduler-enabled",
      havingValue = "true")
  static class ScheduledDelivery {
    private final ReminderUseCases jobs;

    ScheduledDelivery(ReminderUseCases j) {
      jobs = j;
    }

    @Scheduled(
        fixedDelayString = "${atlas.talent.reminders.poll-ms:60000}",
        initialDelayString = "${atlas.talent.reminders.poll-ms:60000}")
    void deliver() {
      try {
        jobs.tick();
      } catch (RuntimeException unavailable) {
        org.slf4j.LoggerFactory.getLogger(ScheduledDelivery.class)
            .warn(
                "talent_reminder_scheduler_unavailable: inspect database health and persisted processing state");
      }
    }
  }
}
