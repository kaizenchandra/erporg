package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.out.AdditionalContactsStore;
import com.atlas.modules.corehr.domain.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcAdditionalContactsStore implements AdditionalContactsStore {
  private final JdbcClient db;

  public JdbcAdditionalContactsStore(JdbcClient db) {
    this.db = db;
  }

  private record Meta(
      String id,
      String employeeId,
      LocalDate effectiveDate,
      String reason,
      String status,
      String requestedBy,
      String requesterEmployeeId,
      long employeeVersion,
      Instant recordedAt,
      String correlationId,
      int version,
      String decidedBy,
      String comment,
      Instant decidedAt,
      String decisionCorrelationId) {}

  private Meta meta(ResultSet r, int row) throws SQLException {
    return new Meta(
        r.getString("id"),
        r.getString("employee_id"),
        r.getObject("effective_date", LocalDate.class),
        r.getString("reason"),
        r.getString("status"),
        r.getString("requested_by"),
        r.getString("requester_employee_id"),
        r.getLong("employee_version"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"),
        r.getInt("version"),
        r.getString("decided_by"),
        r.getString("comment"),
        r.getTimestamp("decided_at") == null ? null : r.getTimestamp("decided_at").toInstant(),
        r.getString("decision_correlation_id"));
  }

  private Map<String, List<EmergencyContact>> items(String tenant, List<String> ids) {
    if (ids.isEmpty()) return Map.of();
    record Item(String requestId, String side, String name, String relationship, String phone) {}
    var result = new HashMap<String, List<EmergencyContact>>();
    for (var i :
        db.sql(
                "select request_id,side,name,relationship,phone from hr_contact_item where tenant=:tenant and request_id in(:ids) order by request_id,side,ordinal")
            .param("tenant", tenant)
            .param("ids", ids)
            .query(Item.class)
            .list())
      result
          .computeIfAbsent(i.requestId() + "/" + i.side(), key -> new ArrayList<>())
          .add(new EmergencyContact(i.name(), i.relationship(), i.phone()));
    return result;
  }

  private ContactRequest map(Meta m, Map<String, List<EmergencyContact>> items) {
    return new ContactRequest(
        m.id(),
        m.employeeId(),
        items.getOrDefault(m.id() + "/OLD", List.of()),
        items.getOrDefault(m.id() + "/NEW", List.of()),
        m.effectiveDate(),
        m.reason(),
        m.status(),
        m.requestedBy(),
        m.requesterEmployeeId(),
        m.employeeVersion(),
        m.recordedAt(),
        m.correlationId(),
        m.version(),
        m.decidedBy(),
        m.comment(),
        m.decidedAt(),
        m.decisionCorrelationId());
  }

  public Current current(String tenant, String employeeId, LocalDate date) {
    var m =
        db.sql(
                "select * from hr_contact_request where tenant=:tenant and employee_id=:employee and status='APPROVED' and effective_date<=:date order by effective_date desc,decided_at desc,id desc limit 1")
            .param("tenant", tenant)
            .param("employee", employeeId)
            .param("date", date)
            .query(this::meta)
            .optional();
    if (m.isEmpty()) return new Current(List.of(), null);
    var r = map(m.get(), items(tenant, List.of(m.get().id())));
    return new Current(r.contacts(), r.effectiveDate());
  }

  public Page inbox(InboxScope scope, Query q) {
    var params =
        Map.<String, Object>of(
            "tenant",
            scope.tenant(),
            "owner",
            scope.ownerEmployee(),
            "excludeEmployee",
            scope.excludeEmployee(),
            "excludeSubject",
            scope.excludeSubject(),
            "status",
            q.status());
    String where =
        " from hr_contact_request where tenant=:tenant and (:owner='' or employee_id=:owner) and employee_id<>:excludeEmployee and requester_employee_id<>:excludeEmployee and requested_by<>:excludeSubject and (:status='ALL' or status=:status)";
    long count = db.sql("select count(*)" + where).params(params).query(Long.class).single();
    var rows =
        db.sql("select *" + where + " order by recorded_at desc,id limit :size offset :offset")
            .params(params)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(this::meta)
            .list();
    var values = items(scope.tenant(), rows.stream().map(Meta::id).toList());
    return new Page(rows.stream().map(m -> map(m, values)).toList(), count, q.page(), q.size());
  }

  public Page page(String tenant, String employeeId, Query q) {
    var params =
        Map.<String, Object>of("tenant", tenant, "employee", employeeId, "status", q.status());
    String where =
        " from hr_contact_request where tenant=:tenant and employee_id=:employee and (:status='ALL' or status=:status)";
    long count = db.sql("select count(*)" + where).params(params).query(Long.class).single();
    var rows =
        db.sql("select *" + where + " order by recorded_at desc,id limit :size offset :offset")
            .params(params)
            .param("size", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(this::meta)
            .list();
    var values = items(tenant, rows.stream().map(Meta::id).toList());
    return new Page(rows.stream().map(m -> map(m, values)).toList(), count, q.page(), q.size());
  }

  public Optional<ContactRequest> duplicate(
      String tenant, String subject, String key, boolean decision) {
    var m =
        db.sql(
                "select * from hr_contact_request where tenant=:tenant and "
                    + (decision
                        ? "decided_by=:subject and decision_key=:key"
                        : "requested_by=:subject and idempotency_key=:key"))
            .param("tenant", tenant)
            .param("subject", subject)
            .param("key", key)
            .query(this::meta)
            .optional();
    return m.map(row -> map(row, items(tenant, List.of(row.id()))));
  }

  public boolean pending(String tenant, String employeeId) {
    return db.sql(
            "select exists(select 1 from hr_contact_request where tenant=:tenant and employee_id=:employee and status='SUBMITTED')")
        .param("tenant", tenant)
        .param("employee", employeeId)
        .query(Boolean.class)
        .single();
  }

  public ContactRequest get(String tenant, String employeeId, String id, boolean lock) {
    var m =
        db.sql(
                "select * from hr_contact_request where tenant=:tenant and employee_id=:employee and id=:id"
                    + (lock ? " for update" : ""))
            .param("tenant", tenant)
            .param("employee", employeeId)
            .param("id", id)
            .query(this::meta)
            .optional()
            .orElseThrow(ChangeRequest.Denied::new);
    return map(m, items(tenant, List.of(id)));
  }

  private void snapshot(String tenant, String id, String side, List<EmergencyContact> values) {
    for (int i = 0; i < values.size(); i++) {
      var c = values.get(i);
      db.sql(
              "insert into hr_contact_item(tenant,request_id,side,ordinal,name,relationship,phone) values(:tenant,:id,:side,:ordinal,:name,:relationship,:phone)")
          .param("tenant", tenant)
          .param("id", id)
          .param("side", side)
          .param("ordinal", i + 1)
          .param("name", c.name())
          .param("relationship", c.relationship())
          .param("phone", c.phone())
          .update();
    }
  }

  private void audit(String tenant, ContactRequest r) {
    boolean submitted = r.status().equals("SUBMITTED");
    db.sql(
            "insert into hr_contact_audit(tenant,request_id,status,actor,reason,effective_date,recorded_at,correlation_id) values(:tenant,:id,:status,:actor,:reason,:date,:now,:correlation)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("status", r.status())
        .param("actor", submitted ? r.requestedBy() : r.decidedBy())
        .param("reason", submitted ? r.reason() : r.comment())
        .param("date", r.effectiveDate())
        .param("now", (submitted ? r.recordedAt() : r.decidedAt()).atOffset(ZoneOffset.UTC))
        .param("correlation", submitted ? r.correlationId() : r.decisionCorrelationId())
        .update();
  }

  public void insert(String tenant, ContactRequest r, String key) {
    db.sql(
            "insert into hr_contact_request(tenant,id,employee_id,effective_date,reason,requested_by,requester_employee_id,employee_version,recorded_at,correlation_id,idempotency_key,old_count,new_count,status,version) values(:tenant,:id,:employee,:date,:reason,:actor,:actorEmployee,:version,:now,:correlation,:key,:old,:new,'SUBMITTED',0)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("employee", r.employeeId())
        .param("date", r.effectiveDate())
        .param("reason", r.reason())
        .param("actor", r.requestedBy())
        .param("actorEmployee", r.requesterEmployeeId())
        .param("version", r.employeeVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .param("old", r.oldContacts().size())
        .param("new", r.contacts().size())
        .update();
    snapshot(tenant, r.id(), "OLD", r.oldContacts());
    snapshot(tenant, r.id(), "NEW", r.contacts());
    audit(tenant, r);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,'Additional emergency contacts were submitted for HR review.',:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  public void decide(
      String tenant, ContactRequest r, String reviewerEmployee, long reviewVersion, String key) {
    if (r.status().equals("APPROVED")) {
      int n =
          db.sql(
                  "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:version")
              .param("tenant", tenant)
              .param("employee", r.employeeId())
              .param("version", r.employeeVersion())
              .update();
      if (n != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh contacts.");
    }
    int n =
        db.sql(
                "update hr_contact_request set status=:status,version=1,decided_by=:actor,reviewer_employee_id=:reviewer,comment=:comment,decided_at=:now,decision_correlation_id=:correlation,review_version=:reviewVersion,decision_key=:key where tenant=:tenant and id=:id and status='SUBMITTED' and version=0")
            .param("tenant", tenant)
            .param("id", r.id())
            .param("status", r.status())
            .param("actor", r.decidedBy())
            .param("reviewer", reviewerEmployee)
            .param("comment", r.comment())
            .param("now", r.decidedAt().atOffset(ZoneOffset.UTC))
            .param("correlation", r.decisionCorrelationId())
            .param("reviewVersion", reviewVersion)
            .param("key", key)
            .update();
    if (n != 1) throw new ChangeRequest.Conflict("Contact request changed. Refresh its history.");
    audit(tenant, r);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,'An additional emergency-contact request was reviewed. Open your profile for its private history.',:now from identity_membership where tenant=:tenant and employee_id=:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param("now", r.decidedAt().atOffset(ZoneOffset.UTC))
        .update();
  }
}
