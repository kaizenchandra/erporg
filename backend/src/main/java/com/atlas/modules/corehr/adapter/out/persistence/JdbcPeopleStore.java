package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.AuditEntry;
import com.atlas.modules.corehr.application.model.DirectoryPage;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.Notification;
import com.atlas.modules.corehr.application.model.PeopleReadScope;
import com.atlas.modules.corehr.application.model.PeopleSummary;
import com.atlas.modules.corehr.application.model.RequestPage;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.application.port.out.PeopleStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcPeopleStore implements PeopleStore {
  private final JdbcClient db;

  public JdbcPeopleStore(JdbcClient db) {
    this.db = db;
  }

  public void lockReportingGraph(String tenant) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:tenant,87301))")
        .param("tenant", tenant)
        .query((rs, row) -> 0)
        .single();
  }

  public List<ReportingLink> reportingLinks(String tenant, LocalDate date, int limit) {
    return db.sql(
            "select e.id,"
                + MANAGER_AT
                + " manager_id,e.start_date from hr_employee e where e.tenant=:tenant order by e.id limit :limit")
        .param("tenant", tenant)
        .param("date", date)
        .param("limit", limit)
        .query(ReportingLink.class)
        .list();
  }

  public List<LocalDate> reportingDates(String tenant, LocalDate from, int limit) {
    return db.sql(
            "select distinct effective_date from hr_version where tenant=:tenant and field='managerId' and effective_date>=:date order by effective_date limit :limit")
        .param("tenant", tenant)
        .param("date", from)
        .param("limit", limit)
        .query(LocalDate.class)
        .list();
  }

  public List<String> managerEmployeeIds(String tenant, List<String> roles) {
    return db.sql(
            "select distinct employee_id from identity_membership where tenant=:tenant and role in (:roles)")
        .param("tenant", tenant)
        .param("roles", roles)
        .query(String.class)
        .list();
  }

  public com.atlas.modules.corehr.application.model.ManagerPage managerCandidates(
      String tenant,
      com.atlas.modules.corehr.application.model.ManagerQuery query,
      List<String> roles,
      LocalDate date) {
    String where =
        " from hr_employee e where e.tenant=:tenant and exists(select 1 from identity_membership m where m.tenant=e.tenant and m.employee_id=e.id and m.role in (:roles)) and strpos(lower("
            + NAME_AT
            + " || ' ' || e.id),lower(:search))>0";
    long total =
        db.sql("select count(*)" + where)
            .param("tenant", tenant)
            .param("date", date)
            .param("roles", roles)
            .param("search", query.search())
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select e.id,"
                    + NAME_AT
                    + " name,e.start_date"
                    + where
                    + " order by name,e.id limit :size offset :offset")
            .param("tenant", tenant)
            .param("date", date)
            .param("roles", roles)
            .param("search", query.search())
            .param("size", query.size())
            .param("offset", (long) query.page() * query.size())
            .query(com.atlas.modules.corehr.application.model.ManagerCandidate.class)
            .list();
    return new com.atlas.modules.corehr.application.model.ManagerPage(
        items, total, query.page(), query.size());
  }

  public PeopleSummary summary(PeopleReadScope a, LocalDate date) {
    return db.sql(
            "select count(*) total, count(*) filter(where e.status='Active') active, count(*) filter(where e.status='On leave') on_leave, (select count(*) from hr_request r join hr_employee target on target.tenant=r.tenant and target.id=r.employee_id where r.tenant=:tenant and r.status='SUBMITTED' and (:all or r.requested_by=:subject or (:reports and target.manager_id=:self)) and (r.employee_id=:self or r.field in (:fields))) pending from hr_employee e where e.tenant=:tenant and (:all or e.id=:self or (:reports and "
                + MANAGER_AT
                + "=:self))")
        .param("tenant", a.tenant())
        .param("date", date)
        .param("all", a.allEmployees())
        .param("reports", a.directReports())
        .param("fields", a.visibleRequestFields())
        .param("subject", a.subject())
        .param("self", a.employeeId())
        .query(PeopleSummary.class)
        .single();
  }

  public Actor actor(String subject, LocalDate date) {
    return db.sql(
            "select m.subject,m.tenant,m.employee_id,"
                + NAME_AT
                + " name,m.role from identity_membership m join hr_employee e on e.tenant=m.tenant and e.id=m.employee_id where m.subject=:s")
        .param("s", subject)
        .param("date", date)
        .query(Actor.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  private static final String NAME_AT =
      "coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='name' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.name)";

  private static final String MANAGER_AT =
      "coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='managerId' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.manager_id)";

  private static final String SELECT =
      "select e.id,"
          + NAME_AT
          + " name,e.email, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='phone' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.phone) phone, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='title' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.title) title, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='department' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.department) department, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='location' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.location) location, e.status,"
          + MANAGER_AT
          + " manager_id,e.start_date,e.version, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='address' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.address) address, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='emergencyContactName' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.emergency_contact_name) emergency_contact_name, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='emergencyContactRelationship' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.emergency_contact_relationship) emergency_contact_relationship, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='emergencyContactPhone' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.emergency_contact_phone) emergency_contact_phone, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='positionCode' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc,v.id desc limit 1),e.position_code) position_code from hr_employee e where e.tenant=:tenant";

  public DirectoryPage directory(PeopleReadScope a, DirectoryQuery q, LocalDate date) {
    String scoped = SELECT + " and (:all or e.id=:self or (:reports and " + MANAGER_AT + "=:self))";
    String filtered =
        "with scoped as ("
            + scoped
            + ") select * from scoped where (:department='' or department=:department) and (:status='' or status=:status) and position(lower(:search) in lower(name || ' ' || id || ' ' || title || ' ' || email))>0";
    var parameters = new HashMap<String, Object>();
    parameters.put("tenant", a.tenant());
    parameters.put("date", date);
    parameters.put("all", a.allEmployees());
    parameters.put("reports", a.directReports());
    parameters.put("self", a.employeeId());
    parameters.put("department", q.department());
    parameters.put("status", q.status());
    parameters.put("search", q.search());
    long total =
        db.sql("select count(*) from (" + filtered + ") filtered")
            .params(parameters)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                filtered
                    + " order by name "
                    + (q.direction().equals("asc") ? "asc" : "desc")
                    + ", id asc limit :limit offset :offset")
            .params(parameters)
            .param("limit", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(Employee.class)
            .list();
    var departments =
        db.sql("select distinct department from (" + scoped + ") scoped order by department")
            .params(parameters)
            .query(String.class)
            .list();
    return new DirectoryPage(items, total, q.page(), q.size(), departments);
  }

  public List<Employee> employees(PeopleReadScope a, LocalDate date) {
    return db.sql(
            SELECT
                + " and (:hr or e.id=:self or (:manager and "
                + MANAGER_AT
                + "=:self)) order by name,e.id limit 1000")
        .param("tenant", a.tenant())
        .param("date", date)
        .param("hr", a.allEmployees())
        .param("manager", a.directReports())
        .param("self", a.employeeId())
        .query(Employee.class)
        .list();
  }

  public Employee employee(Actor a, String id, LocalDate date, boolean lock) {
    if (lock)
      db.sql("select id from hr_employee where tenant=:t and id=:id for update")
          .param("t", a.tenant())
          .param("id", id)
          .query(String.class)
          .optional()
          .orElseThrow(ChangeRequest.Denied::new);
    Employee e =
        db.sql(SELECT + " and e.id=:id")
            .param("tenant", a.tenant())
            .param("date", date)
            .param("id", id)
            .query(Employee.class)
            .optional()
            .orElseThrow(ChangeRequest.Denied::new);
    return e;
  }

  private static final String REQUEST =
      "select r.id,r.employee_id,"
          + NAME_AT
          + " employee_name,r.field,r.old_value,r.new_value,r.effective_date,r.reason,r.status,r.requested_by,r.employee_version,r.recorded_at,r.decided_by,r.comment from hr_request r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where r.tenant=:t";

  public RequestPage requestPage(PeopleReadScope a, RequestQuery q, LocalDate date) {
    String scoped =
        REQUEST
            + " and (:all or r.requested_by=:subject or (:reports and "
            + MANAGER_AT
            + "=:self)) and (r.employee_id=:self or r.field in (:fields)) and (:status='ALL' or r.status=:status)";
    var params =
        Map.<String, Object>of(
            "t",
            a.tenant(),
            "date",
            date,
            "all",
            a.allEmployees(),
            "subject",
            a.subject(),
            "reports",
            a.directReports(),
            "self",
            a.employeeId(),
            "status",
            q.status(),
            "fields",
            a.visibleRequestFields());
    long total =
        db.sql("select count(*) from (" + scoped + ") scoped")
            .params(params)
            .query(Long.class)
            .single();
    var items =
        db.sql(scoped + " order by r.recorded_at desc, r.id asc limit :limit offset :offset")
            .params(params)
            .param("limit", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(ChangeRequest.class)
            .list();
    return new RequestPage(items, total, q.page(), q.size());
  }

  public List<ChangeRequest> requests(PeopleReadScope a, LocalDate date) {
    return db.sql(
            REQUEST
                + " and (:hr or r.requested_by=:s or (:manager and "
                + MANAGER_AT
                + "=:self)) and (r.employee_id=:self or r.field in (:fields)) order by r.recorded_at desc limit 500")
        .param("t", a.tenant())
        .param("date", date)
        .param("hr", a.allEmployees())
        .param("s", a.subject())
        .param("manager", a.directReports())
        .param("self", a.employeeId())
        .param("fields", a.visibleRequestFields())
        .query(ChangeRequest.class)
        .list();
  }

  public Optional<ChangeRequest> duplicate(Actor a, String key, LocalDate date) {
    return db.sql(REQUEST + " and r.requested_by=:s and r.idempotency_key=:key")
        .param("t", a.tenant())
        .param("date", date)
        .param("s", a.subject())
        .param("key", key)
        .query(ChangeRequest.class)
        .optional();
  }

  public ChangeRequest request(Actor a, String id, boolean lock, LocalDate date) {
    return db.sql(REQUEST + " and r.id=:id" + (lock ? " for update of r" : ""))
        .param("t", a.tenant())
        .param("date", date)
        .param("id", id)
        .query(ChangeRequest.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  public void insert(Actor a, ChangeRequest r, String key, String correlation) {
    db.sql(
            "insert into hr_request(id,tenant,employee_id,field,old_value,new_value,effective_date,reason,status,requested_by,employee_version,recorded_at,idempotency_key) values(:id,:t,:e,:f,:old,:new,:date,:reason,'SUBMITTED',:actor,:v,:now,:key)")
        .param("id", r.id())
        .param("t", a.tenant())
        .param("e", r.employeeId())
        .param("f", r.field())
        .param("old", r.oldValue())
        .param("new", r.newValue())
        .param("date", r.effectiveDate())
        .param("reason", r.reason())
        .param("actor", a.subject())
        .param("v", r.employeeVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("key", key)
        .update();
    audit(a, r, "SUBMITTED", r.reason(), r.recordedAt(), correlation);
  }

  public List<LocalDate> employmentChangeDates(Actor actor, String employeeId, LocalDate from) {
    return db.sql(
            "select distinct effective_date from hr_version where tenant=:tenant and employee_id=:employee and field in ('department','positionCode') and effective_date>=:date order by effective_date")
        .param("tenant", actor.tenant())
        .param("employee", employeeId)
        .param("date", from)
        .query(LocalDate.class)
        .list();
  }

  public boolean hasLaterChange(Actor a, ChangeRequest r) {
    return db.sql(
            "select exists(select 1 from hr_version where tenant=:t and employee_id=:e and field=:f and effective_date>:date)")
        .param("t", a.tenant())
        .param("e", r.employeeId())
        .param("f", r.field())
        .param("date", r.effectiveDate())
        .query(Boolean.class)
        .single();
  }

  public Optional<com.atlas.modules.corehr.application.model.Withdrawal> withdrawalReplay(
      Actor a, String key) {
    return db.sql(
            "select request_id,actor,reason,idempotency_key,recorded_at,correlation_id from hr_request_withdrawal where tenant=:tenant and actor=:actor and idempotency_key=:key")
        .param("tenant", a.tenant())
        .param("actor", a.subject())
        .param("key", key)
        .query(com.atlas.modules.corehr.application.model.Withdrawal.class)
        .optional();
  }

  public void withdraw(
      Actor a, ChangeRequest r, com.atlas.modules.corehr.application.model.Withdrawal w) {
    db.sql(
            "update hr_request set status='WITHDRAWN',decided_by=:actor,comment=:reason where tenant=:tenant and id=:id")
        .param("actor", a.subject())
        .param("reason", w.reason())
        .param("tenant", a.tenant())
        .param("id", r.id())
        .update();
    db.sql(
            "insert into hr_request_withdrawal(tenant,request_id,actor,reason,idempotency_key,recorded_at,correlation_id) values(:tenant,:id,:actor,:reason,:key,:now,:correlation)")
        .param("tenant", a.tenant())
        .param("id", r.id())
        .param("actor", a.subject())
        .param("reason", w.reason())
        .param("key", w.idempotencyKey())
        .param("now", w.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", w.correlationId())
        .update();
    audit(a, r, "WITHDRAWN", w.reason(), w.recordedAt(), w.correlationId());
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:tenant,:actor,:message,:now)")
        .param("tenant", a.tenant())
        .param("actor", a.subject())
        .param("message", r.employeeName() + " · change request withdrawn")
        .param("now", w.recordedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  public void decide(
      Actor a, ChangeRequest r, String decision, String comment, Instant now, String correlation) {
    if (decision.equals("APPROVED")) {
      db.sql(
              "insert into hr_version(tenant,employee_id,field,value,effective_date,recorded_at,request_id) values(:t,:e,:f,:v,:date,:now,:r)")
          .param("t", a.tenant())
          .param("e", r.employeeId())
          .param("f", r.field())
          .param("v", r.newValue())
          .param("date", r.effectiveDate())
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("r", r.id())
          .update();
      db.sql("update hr_employee set version=version+1 where tenant=:t and id=:e")
          .param("t", a.tenant())
          .param("e", r.employeeId())
          .update();
    }
    db.sql(
            "update hr_request set status=:status,decided_by=:actor,comment=:comment where tenant=:t and id=:id")
        .param("status", decision)
        .param("actor", a.subject())
        .param("comment", comment)
        .param("t", a.tenant())
        .param("id", r.id())
        .update();
    if (decision.equals("APPROVED") && r.field().equals("positionCode")) {
      db.sql(
              "insert into hr_position_assignment(tenant,employee_id,position_code,effective_date,recorded_at,request_id) values(:tenant,:employee,:position,:date,:now,:request)")
          .param("tenant", a.tenant())
          .param("employee", r.employeeId())
          .param("position", r.newValue().isEmpty() ? null : r.newValue())
          .param("date", r.effectiveDate())
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("request", r.id())
          .update();
    }
    if (decision.equals("APPROVED") && r.field().equals("managerId")) {
      db.sql(
              "insert into hr_reporting_assignment(tenant,employee_id,manager_id,effective_date,recorded_at,request_id) values(:tenant,:employee,:manager,:date,:now,:request)")
          .param("tenant", a.tenant())
          .param("employee", r.employeeId())
          .param("manager", r.newValue())
          .param("date", r.effectiveDate())
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("request", r.id())
          .update();
    }
    audit(a, r, decision, comment, now, correlation);
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) values(:t,:subject,:message,:now)")
        .param("t", a.tenant())
        .param("subject", r.requestedBy())
        .param(
            "message", r.employeeName() + " · " + r.field() + " change " + decision.toLowerCase())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }

  private void audit(
      Actor a, ChangeRequest r, String action, String reason, Instant now, String correlation) {
    db.sql(
            "insert into hr_audit(tenant,employee_id,request_id,actor,action,field,old_value,new_value,reason,recorded_at,correlation_id) values(:t,:e,:r,:actor,:action,:f,:old,:new,:reason,:now,:c)")
        .param("t", a.tenant())
        .param("e", r.employeeId())
        .param("r", r.id())
        .param("actor", a.subject())
        .param("action", action)
        .param("f", r.field())
        .param("old", r.oldValue())
        .param("new", r.newValue())
        .param("reason", reason)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("c", correlation)
        .update();
  }

  public List<AuditEntry> history(Actor a, String id) {
    return db.sql(
            "select a.action,a.field,a.old_value,a.new_value,a.reason,a.actor,a.recorded_at,a.correlation_id,r.effective_date from hr_audit a join hr_request r on r.id=a.request_id and r.tenant=a.tenant where a.tenant=:t and a.employee_id=:id order by a.recorded_at desc limit 100")
        .param("t", a.tenant())
        .param("id", id)
        .query(AuditEntry.class)
        .list();
  }

  public List<Notification> notifications(Actor a) {
    return db.sql(
            "select id,message,recorded_at from hr_notification where tenant=:t and subject=:s order by recorded_at desc limit 50")
        .param("t", a.tenant())
        .param("s", a.subject())
        .query(Notification.class)
        .list();
  }
}
