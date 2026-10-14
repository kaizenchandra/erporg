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

  public PeopleSummary summary(PeopleReadScope a) {
    return db.sql(
            "select count(*) total, count(*) filter(where e.status='Active') active, count(*) filter(where e.status='On leave') on_leave, (select count(*) from hr_request r join hr_employee target on target.tenant=r.tenant and target.id=r.employee_id where r.tenant=:tenant and r.status='SUBMITTED' and (:all or r.requested_by=:subject or (:reports and target.manager_id=:self))) pending from hr_employee e where e.tenant=:tenant and (:all or e.id=:self or (:reports and e.manager_id=:self))")
        .param("tenant", a.tenant())
        .param("all", a.allEmployees())
        .param("reports", a.directReports())
        .param("subject", a.subject())
        .param("self", a.employeeId())
        .query(PeopleSummary.class)
        .single();
  }

  public Actor actor(String subject) {
    return db.sql(
            "select subject,tenant,employee_id,name,role from identity_membership where subject=:s")
        .param("s", subject)
        .query(Actor.class)
        .optional()
        .orElseThrow(ChangeRequest.Denied::new);
  }

  private static final String SELECT =
      "select e.id,e.name,e.email, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='phone' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc limit 1),e.phone) phone, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='title' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc limit 1),e.title) title, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='department' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc limit 1),e.department) department, coalesce((select v.value from hr_version v where v.tenant=e.tenant and v.employee_id=e.id and v.field='location' and v.effective_date<=:date order by v.effective_date desc,v.recorded_at desc limit 1),e.location) location, e.status,e.manager_id,e.start_date,e.version from hr_employee e where e.tenant=:tenant";

  public DirectoryPage directory(PeopleReadScope a, DirectoryQuery q, LocalDate date) {
    String scoped = SELECT + " and (:all or e.id=:self or (:reports and e.manager_id=:self))";
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
                + " and (:hr or e.id=:self or (:manager and e.manager_id=:self)) order by e.name limit 1000")
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
      "select r.id,r.employee_id,e.name employee_name,r.field,r.old_value,r.new_value,r.effective_date,r.reason,r.status,r.requested_by,r.employee_version,r.recorded_at,r.decided_by,r.comment from hr_request r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where r.tenant=:t";

  public RequestPage requestPage(PeopleReadScope a, RequestQuery q) {
    String scoped =
        REQUEST
            + " and (:all or r.requested_by=:subject or (:reports and e.manager_id=:self)) and (:status='ALL' or r.status=:status)";
    var params =
        Map.<String, Object>of(
            "t",
            a.tenant(),
            "all",
            a.allEmployees(),
            "subject",
            a.subject(),
            "reports",
            a.directReports(),
            "self",
            a.employeeId(),
            "status",
            q.status());
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

  public List<ChangeRequest> requests(PeopleReadScope a) {
    return db.sql(
            REQUEST
                + " and (:hr or r.requested_by=:s or (:manager and e.manager_id=:self)) order by r.recorded_at desc limit 500")
        .param("t", a.tenant())
        .param("hr", a.allEmployees())
        .param("s", a.subject())
        .param("manager", a.directReports())
        .param("self", a.employeeId())
        .query(ChangeRequest.class)
        .list();
  }

  public Optional<ChangeRequest> duplicate(Actor a, String key) {
    return db.sql(REQUEST + " and r.requested_by=:s and r.idempotency_key=:key")
        .param("t", a.tenant())
        .param("s", a.subject())
        .param("key", key)
        .query(ChangeRequest.class)
        .optional();
  }

  public ChangeRequest request(Actor a, String id, boolean lock) {
    return db.sql(REQUEST + " and r.id=:id" + (lock ? " for update of r" : ""))
        .param("t", a.tenant())
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
