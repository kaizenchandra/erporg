package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.application.port.out.LegalEntityStore;
import com.atlas.modules.corehr.domain.model.LegalEntity;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcLegalEntityStore implements LegalEntityStore {
  private final JdbcClient db;

  public JdbcLegalEntityStore(JdbcClient db) {
    this.db = db;
  }

  public boolean assignmentRecorded(String tenant, String code) {
    return db.sql(
            "select exists(select 1 from hr_legal_entity_assignment where tenant=:tenant and entity_code=:code)")
        .param("tenant", tenant)
        .param("code", code)
        .query(Boolean.class)
        .single();
  }

  public void lock(String tenant) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
        .param("scope", "corehr:legal-entity:" + tenant)
        .query((rs, n) -> true)
        .single();
  }

  public Optional<Entry> find(String tenant, String code) {
    return db.sql("select * from hr_legal_entity where tenant=:tenant and code=:code")
        .param("tenant", tenant)
        .param("code", code)
        .query((r, n) -> entry(r))
        .optional();
  }

  public long count(String tenant) {
    return db.sql("select count(*) from hr_legal_entity where tenant=:tenant")
        .param("tenant", tenant)
        .query(Long.class)
        .single();
  }

  public boolean codeExists(String tenant, String code) {
    return db.sql(
            "select exists(select 1 from hr_legal_entity where tenant=:tenant and code=:code)")
        .param("tenant", tenant)
        .param("code", code)
        .query(Boolean.class)
        .single();
  }

  public Optional<Entry> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from hr_legal_entity where tenant=:tenant and created_by=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> entry(r))
        .optional();
  }

  private Entry entry(ResultSet r) throws SQLException {
    return new Entry(
        new LegalEntity(
            r.getString("code"),
            r.getString("name"),
            r.getString("country_code"),
            r.getString("currency_code"),
            r.getString("time_zone"),
            r.getObject("effective_from", LocalDate.class)),
        r.getString("created_by"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  private record ReferenceRow(Entry entry, LocalDate retirementDate) {}

  public Page list(String tenant, Query q, LocalDate today) {
    String where =
        " from hr_legal_entity e left join hr_legal_entity_retirement_current r on r.tenant=e.tenant and r.code=e.code where e.tenant=:tenant and (:search='' or strpos(lower(e.code||' '||e.name),lower(:search))>0) and (:status='ALL' or (:status='CURRENT' and e.effective_from<=:today and (r.retirement_date is null or r.retirement_date>:today)) or (:status='PLANNED' and e.effective_from>:today and (r.retirement_date is null or r.retirement_date>:today)) or (:status='RETIRED' and r.retirement_date<=:today))";
    long total =
        db.sql("select count(*)" + where)
            .param("tenant", tenant)
            .param("search", q.search())
            .param("status", q.status())
            .param("today", today)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select e.*,r.retirement_date"
                    + where
                    + " order by e.code collate \"C\" limit :size offset :offset")
            .param("tenant", tenant)
            .param("search", q.search())
            .param("status", q.status())
            .param("today", today)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(
                (r, n) ->
                    new ReferenceRow(entry(r), r.getObject("retirement_date", LocalDate.class)))
            .list();
    var dates = new java.util.LinkedHashMap<String, LocalDate>();
    items.stream()
        .filter(row -> row.retirementDate() != null)
        .forEach(row -> dates.put(row.entry().entity().code(), row.retirementDate()));
    return new Page(
        items.stream().map(ReferenceRow::entry).toList(),
        total,
        q.page(),
        q.size(),
        java.util.Map.copyOf(dates));
  }

  public void insert(String tenant, Entry e, String key) {
    var v = e.entity();
    db.sql(
            "insert into hr_legal_entity(tenant,code,name,country_code,currency_code,time_zone,effective_from,created_by,reason,idempotency_key,recorded_at,correlation_id) values(:tenant,:code,:name,:country,:currency,:zone,:date,:actor,:reason,:key,:now,:correlation)")
        .param("tenant", tenant)
        .param("code", v.code())
        .param("name", v.name())
        .param("country", v.countryCode())
        .param("currency", v.currencyCode())
        .param("zone", v.timeZone())
        .param("date", v.effectiveFrom())
        .param("actor", e.createdBy())
        .param("reason", e.reason())
        .param("key", key)
        .param("now", e.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", e.correlationId())
        .update();
  }
}
