package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.PositionDirectoryModels.*;
import com.atlas.modules.corehr.application.model.PositionListing;
import com.atlas.modules.corehr.application.port.out.PositionDirectoryStore;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcPositionDirectoryStore implements PositionDirectoryStore {
  private final JdbcClient db;

  public JdbcPositionDirectoryStore(JdbcClient db) {
    this.db = db;
  }

  private JdbcClient.StatementSpec filtered(String sql, String tenant, Query q, LocalDate date) {
    return db.sql(sql)
        .param("tenant", tenant)
        .param("search", q.search())
        .param("department", q.departmentCode())
        .param("timing", q.timing())
        .param("date", date);
  }

  public Batch browse(String tenant, Query q, LocalDate date) {
    String from =
        " from hr_position p join hr_department d on d.tenant=p.tenant and d.code=p.department_code where p.tenant=:tenant and (:department='' or p.department_code=:department) and (:search='' or position(lower(:search) in lower(p.code||' '||p.title))>0) and (:timing='ALL' or (:timing='CURRENT' and p.effective_from<=:date) or (:timing='PLANNED' and p.effective_from>:date))";
    long total = filtered("select count(*)" + from, tenant, q, date).query(Long.class).single();
    var items =
        filtered(
                "select p.code,p.title,p.department_code,d.name department_name,p.effective_from"
                    + from
                    + " order by p.title collate \"C\" "
                    + q.direction()
                    + ",p.code collate \"C\" asc limit :size offset :offset",
                tenant,
                q,
                date)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(PositionListing.class)
            .list();
    return new Batch(items, total);
  }
}
