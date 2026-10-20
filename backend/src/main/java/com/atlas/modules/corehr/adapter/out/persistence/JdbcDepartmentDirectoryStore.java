package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.DepartmentDirectoryModels.*;
import com.atlas.modules.corehr.application.port.out.DepartmentDirectoryStore;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcDepartmentDirectoryStore implements DepartmentDirectoryStore {
  private final JdbcClient db;

  public JdbcDepartmentDirectoryStore(JdbcClient db) {
    this.db = db;
  }

  public Page browse(String tenant, Query q, LocalDate date) {
    String projection =
        "with latest as materialized (select distinct on(code) code,parent_code from hr_department_parent_change where tenant=:tenant and effective_date<=:date order by code,version desc), projected as materialized (select d.tenant,d.code,d.name,case when c.code is null then d.parent_code else c.parent_code end parent_code,d.cost_centre,d.effective_from from hr_department d left join latest c on c.code=d.code where d.tenant=:tenant) ";
    String where =
        " from projected d where d.tenant=:tenant and (:search='' or strpos(lower(d.code || ' ' || d.name),lower(:search))>0) and (:timing='ALL' or (:timing='CURRENT' and d.effective_from<=:date) or (:timing='PLANNED' and d.effective_from>:date)) and (:relationship='ALL' or d.parent_code is null) and (:parent='' or d.parent_code=:parent)";
    var params =
        Map.of(
            "tenant",
            tenant,
            "search",
            q.search(),
            "timing",
            q.timing(),
            "date",
            date,
            "relationship",
            q.relationship(),
            "parent",
            q.parentCode());
    long total =
        db.sql(projection + "select count(*)" + where).params(params).query(Long.class).single();
    var rows =
        db.sql(
                projection
                    + "select d.code,d.name,d.parent_code,(select p.name from projected p where p.tenant=d.tenant and p.code=d.parent_code) parent_name,d.cost_centre,d.effective_from,case when d.effective_from<=:date then 'CURRENT' else 'PLANNED' end timing,(select count(*) from projected c where c.tenant=d.tenant and c.parent_code=d.code) direct_children"
                    + where
                    + " order by d.name "
                    + q.direction()
                    + ",d.code "
                    + q.direction()
                    + " limit :size offset :offset")
            .params(params)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(Item.class)
            .list();
    return new Page(rows, total, q.page(), q.size(), date);
  }
}
