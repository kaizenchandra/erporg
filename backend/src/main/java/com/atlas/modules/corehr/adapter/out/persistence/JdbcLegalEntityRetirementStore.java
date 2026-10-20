package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.NotificationMember;
import com.atlas.modules.corehr.application.port.out.LegalEntityRetirementStore;
import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcLegalEntityRetirementStore implements LegalEntityRetirementStore {
  private final JdbcClient db;

  public JdbcLegalEntityRetirementStore(JdbcClient db) {
    this.db = db;
  }

  public InboxPage inbox(InboxScope scope, InboxQuery query) {
    String where =
        " from hr_legal_entity_retirement_request r left join hr_legal_entity_retirement_decision d on d.tenant=r.tenant and d.request_id=r.id where r.tenant=:tenant and ((:own and r.requested_by=:subject and r.requester_employee_id=:employee) or (not :own and r.requested_by<>:subject and r.requester_employee_id<>:employee)) and (:status='ALL' or coalesce(d.decision,'PENDING')=:status)";
    var parameters =
        Map.<String, Object>of(
            "tenant",
            scope.tenant(),
            "subject",
            scope.subject(),
            "employee",
            scope.employeeId(),
            "own",
            scope.own(),
            "status",
            query.status());
    long total = db.sql("select count(*)" + where).params(parameters).query(Long.class).single();
    var proposals =
        db.sql(
                "select r.*"
                    + where
                    + " order by r.recorded_at desc,r.id collate \"C\" desc limit :size offset :offset")
            .params(parameters)
            .param("size", query.size())
            .param("offset", (long) query.page() * query.size())
            .query((r, n) -> proposal(r))
            .list();
    if (proposals.isEmpty()) return new InboxPage(List.of(), total, query.page(), query.size());
    var decisions =
        db.sql(
                "select * from hr_legal_entity_retirement_decision where tenant=:tenant and request_id in (:ids)")
            .param("tenant", scope.tenant())
            .param("ids", proposals.stream().map(Proposal::id).toList())
            .query((r, n) -> decision(r))
            .list();
    var items =
        proposals.stream()
            .map(
                p ->
                    new State(
                        p,
                        decisions.stream()
                            .filter(d -> d.requestId().equals(p.id()))
                            .findFirst()
                            .orElse(null)))
            .toList();
    return new InboxPage(items, total, query.page(), query.size());
  }

  public List<NotificationMember> notificationCandidates(String tenant) {
    return db.sql(
            "select subject,tenant,employee_id,role from identity_membership where tenant=:tenant and role='HR' order by subject limit 1001")
        .param("tenant", tenant)
        .query(NotificationMember.class)
        .list();
  }

  public void notify(String tenant, String subject, String message, Instant recordedAt) {
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:tenant,:subject,:message,:now)")
        .param("tenant", tenant)
        .param("subject", subject)
        .param("message", message)
        .param("now", recordedAt.atOffset(ZoneOffset.UTC))
        .update();
  }

  private Proposal proposal(ResultSet r) throws SQLException {
    return new Proposal(
        r.getString("id"),
        r.getString("code"),
        r.getInt("ordinal"),
        r.getLong("source_version"),
        r.getObject("effective_date", LocalDate.class),
        r.getString("reason"),
        r.getString("requested_by"),
        r.getString("requester_employee_id"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  private Decision decision(ResultSet r) throws SQLException {
    return new Decision(
        r.getString("request_id"),
        r.getString("decision"),
        r.getString("comment"),
        r.getString("actor"),
        r.getString("actor_employee_id"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"));
  }

  public List<State> history(String tenant, String code) {
    var proposals =
        db.sql(
                "select * from hr_legal_entity_retirement_request where tenant=:tenant and code=:code order by ordinal limit 20")
            .param("tenant", tenant)
            .param("code", code)
            .query((r, n) -> proposal(r))
            .list();
    var decisions =
        db.sql(
                "select * from hr_legal_entity_retirement_decision where tenant=:tenant and code=:code")
            .param("tenant", tenant)
            .param("code", code)
            .query((r, n) -> decision(r))
            .list();
    return proposals.stream()
        .map(
            p ->
                new State(
                    p,
                    decisions.stream()
                        .filter(d -> d.requestId().equals(p.id()))
                        .findFirst()
                        .orElse(null)))
        .toList();
  }

  public Optional<Proposal> duplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from hr_legal_entity_retirement_request where tenant=:tenant and requested_by=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> proposal(r))
        .optional();
  }

  public Optional<Decision> duplicateDecision(String tenant, String actor, String key) {
    return db.sql(
            "select * from hr_legal_entity_retirement_decision where tenant=:tenant and actor=:actor and idempotency_key=:key")
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query((r, n) -> decision(r))
        .optional();
  }

  public void insert(String tenant, Proposal p, String key) {
    db.sql(
            "insert into hr_legal_entity_retirement_request(tenant,id,code,ordinal,source_version,effective_date,reason,requested_by,requester_employee_id,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:code,:ordinal,:version,:date,:reason,:actor,:employee,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", p.id())
        .param("code", p.code())
        .param("ordinal", p.ordinal())
        .param("version", p.sourceVersion())
        .param("date", p.effectiveDate())
        .param("reason", p.reason())
        .param("actor", p.requestedBy())
        .param("employee", p.requesterEmployeeId())
        .param("now", p.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", p.correlationId())
        .param("key", key)
        .update();
  }

  public void insertDecision(String tenant, String code, Decision d, String key) {
    db.sql(
            "insert into hr_legal_entity_retirement_decision(tenant,request_id,code,decision,comment,actor,actor_employee_id,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:code,:decision,:comment,:actor,:employee,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", d.requestId())
        .param("code", code)
        .param("decision", d.decision())
        .param("comment", d.comment())
        .param("actor", d.actor())
        .param("employee", d.actorEmployeeId())
        .param("now", d.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", d.correlationId())
        .param("key", key)
        .update();
  }
}
