package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.EmployeeCalendarModels.*;
import com.atlas.modules.corehr.application.port.out.EmployeeCalendarHistoryStore;
import java.time.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads only Core HR's immutable assignment ledger and owned reference catalogue. */
public final class JdbcEmployeeCalendarHistoryStore implements EmployeeCalendarHistoryStore {
  private final JdbcClient db;

  public JdbcEmployeeCalendarHistoryStore(JdbcClient db) {
    this.db = db;
  }

  public History history(
      String tenant, String employeeId, LocalDate asOf, LocalDate episodeStart, HistoryQuery q) {
    long total =
        db.sql(
                "select count(*) from hr_calendar_assignment where tenant=:tenant and employee_id=:id")
            .param("tenant", tenant)
            .param("id", employeeId)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                """
        select a.version_id,a.episode_start,a.effective_date,a.recorded_at,
               coalesce(a.calendar_code,'') as calendar_code,
               coalesce(c.name,'') as calendar_name,coalesce(c.time_zone,'') as time_zone
        from hr_calendar_assignment a
        left join hr_work_calendar c on c.tenant=a.tenant and c.code=a.calendar_code
        where a.tenant=:tenant and a.employee_id=:id
        order by a.episode_start desc,a.effective_date desc,a.recorded_at desc,a.version_id desc
        limit :size offset :offset
        """)
            .param("tenant", tenant)
            .param("id", employeeId)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(
                (r, n) ->
                    new Assignment(
                        r.getString("version_id"),
                        r.getObject("episode_start", LocalDate.class),
                        r.getObject("effective_date", LocalDate.class),
                        r.getTimestamp("recorded_at").toInstant(),
                        r.getString("calendar_code"),
                        r.getString("calendar_name"),
                        r.getString("time_zone")))
            .list();
    return new History(employeeId, asOf, episodeStart, items, total, q.page(), q.size());
  }
}
