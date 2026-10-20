package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarStore;
import com.atlas.modules.corehr.application.port.in.CalendarsUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.CalendarsService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class CalendarsConfiguration {
  private UnitOfWork boundary(PlatformTransactionManager manager, boolean read) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    t.setReadOnly(read);
    t.setIsolationLevel(
        read
            ? TransactionDefinition.ISOLATION_REPEATABLE_READ
            : TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new UnitOfWork() {
      public <T> T run(Supplier<T> work) {
        return t.execute(s -> work.get());
      }
    };
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.EmployeeCalendarUseCases employeeCalendars(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new com.atlas.modules.corehr.application.usecase.EmployeeCalendarService(
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcPeopleStore(db),
        new JdbcCalendarStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcEmployeeCalendarHistoryStore(db),
        boundary(manager, true),
        clock);
  }

  @Bean
  CalendarsUseCases companyCalendars(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new CalendarsService(
        new JdbcCalendarStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(db),
        boundary(manager, false),
        boundary(manager, true),
        clock);
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.CalendarAmendmentsUseCases calendarAmendments(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new com.atlas.modules.corehr.application.usecase.CalendarAmendmentsService(
        new JdbcCalendarStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(db),
        boundary(manager, false),
        boundary(manager, true),
        clock);
  }

  @Bean
  com.atlas.modules.corehr.application.port.in.CalendarRetirementUseCases calendarRetirements(
      JdbcClient db, PlatformTransactionManager manager, Clock clock) {
    return new com.atlas.modules.corehr.application.usecase.CalendarRetirementService(
        new JdbcCalendarStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarAmendmentStore(db),
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcCalendarRetirementStore(db),
        boundary(manager, false),
        clock);
  }
}
