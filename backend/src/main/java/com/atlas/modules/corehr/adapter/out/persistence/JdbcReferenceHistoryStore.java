package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.ReferenceHistoryModels.*;
import com.atlas.modules.corehr.application.port.out.ReferenceHistoryStore;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcReferenceHistoryStore implements ReferenceHistoryStore {
  private final JdbcClient db;

  public JdbcReferenceHistoryStore(JdbcClient db) {
    this.db = db;
  }

  public Page history(String tenant, Query q) {
    String table = q.kind().equals("DEPARTMENT") ? "hr_department_audit" : "hr_position_audit";
    String from = " from " + table + " where tenant=:tenant and (:code='' or code=:code)";
    long through =
        q.throughId() == null
            ? db.sql("select coalesce(max(id),0)" + from)
                .param("tenant", tenant)
                .param("code", q.code())
                .query(Long.class)
                .single()
            : Long.parseLong(q.throughId());
    long total =
        db.sql("select count(*)" + from + " and id<=:through")
            .param("tenant", tenant)
            .param("code", q.code())
            .param("through", through)
            .query(Long.class)
            .single();
    var rows =
        db.sql(
                "select id::text,code,actor,reason,recorded_at,effective_from,correlation_id"
                    + from
                    + " and id<=:through order by recorded_at "
                    + q.order()
                    + ",id "
                    + q.order()
                    + " limit :size offset :offset")
            .param("tenant", tenant)
            .param("code", q.code())
            .param("through", through)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(Item.class)
            .list();
    return new Page(rows, total, q.page(), q.size(), q.kind(), Long.toString(through));
  }
}
