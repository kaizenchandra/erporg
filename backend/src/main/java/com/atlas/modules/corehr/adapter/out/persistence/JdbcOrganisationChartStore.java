package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.port.out.OrganisationChartStore;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcOrganisationChartStore implements OrganisationChartStore {
  private final JdbcClient db;

  public JdbcOrganisationChartStore(JdbcClient db) {
    this.db = db;
  }

  private static String effective(String field, String original) {
    return "hr_employment_value(e.tenant,e.id,'" + field + "',e." + original + ",:date)";
  }

  private static final String SCOPE =
      "with projected as (select e.id,"
          + effective("name", "name")
          + " name,"
          + effective("title", "title")
          + " title,"
          + effective("department", "department")
          + " department,"
          + effective("positionCode", "position_code")
          + " position_code,hr_employment_status(e.tenant,e.id,e.status,:date) status,"
          + effective("managerId", "manager_id")
          + " manager_id from hr_employee e where e.tenant=:tenant), scoped as (select * from projected where :all or id=:self or (:reports and manager_id=:self)) ";
  private static final String COLUMNS =
      "s.*,(select count(*) from scoped c where c.manager_id=s.id) visible_direct_reports";

  private JdbcClient.StatementSpec scoped(String sql, PeopleReadScope scope, LocalDate date) {
    return db.sql(sql)
        .param("tenant", scope.tenant())
        .param("self", scope.employeeId())
        .param("all", scope.allEmployees())
        .param("reports", scope.directReports())
        .param("date", date);
  }

  private OrganisationNode node(ResultSet r) throws SQLException {
    return new OrganisationNode(
        r.getString("id"),
        r.getString("name"),
        r.getString("title"),
        r.getString("department"),
        r.getString("position_code"),
        r.getString("status"),
        r.getLong("visible_direct_reports"));
  }

  public Optional<OrganisationEntry> find(PeopleReadScope scope, String id, LocalDate date) {
    return scoped(SCOPE + "select " + COLUMNS + " from scoped s where s.id=:id", scope, date)
        .param("id", id)
        .query((r, n) -> new OrganisationEntry(node(r), r.getString("manager_id")))
        .optional();
  }

  public Page page(PeopleReadScope scope, OrganisationChartQuery q, LocalDate date) {
    String condition =
        !q.search().isEmpty()
            ? "position(lower(:search) in lower(s.name||' '||s.id||' '||s.title||' '||s.department))>0"
            : !q.parentId().isEmpty()
                ? "s.manager_id=:parent"
                : "(:all and (s.manager_id is null or not exists(select 1 from scoped m where m.id=s.manager_id))) or (not :all and s.id=:self)";
    var count = scoped(SCOPE + "select count(*) from scoped s where " + condition, scope, date);
    var rows =
        scoped(
                SCOPE
                    + "select "
                    + COLUMNS
                    + " from scoped s where "
                    + condition
                    + " order by lower(s.name),s.id limit :size offset :offset",
                scope,
                date)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size());
    if (!q.search().isEmpty()) {
      count.param("search", q.search());
      rows.param("search", q.search());
    } else if (!q.parentId().isEmpty()) {
      count.param("parent", q.parentId());
      rows.param("parent", q.parentId());
    }
    return new Page(rows.query((r, n) -> node(r)).list(), count.query(Long.class).single());
  }
}
