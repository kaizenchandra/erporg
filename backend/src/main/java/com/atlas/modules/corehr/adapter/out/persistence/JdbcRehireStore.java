package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.RehireModels.Episode;
import com.atlas.modules.corehr.application.port.out.RehireStore;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcRehireStore implements RehireStore {
  private final JdbcClient db;

  public JdbcRehireStore(JdbcClient db) {
    this.db = db;
  }

  private static final String SELECT =
      "select id,employee_id,start_date,title,department,location,manager_id,prior_inactive_date,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id from hr_rehire_current r where tenant=:tenant";

  public List<Rehire> history(String tenant, String employee) {
    return db.sql(SELECT + " and employee_id=:employee order by recorded_at,id")
        .param("tenant", tenant)
        .param("employee", employee)
        .query(Rehire.class)
        .list();
  }

  public Optional<Rehire> duplicate(String tenant, String actor, String key, boolean review) {
    String where =
        review
            ? " and exists(select 1 from hr_rehire_decision d where d.tenant=r.tenant and d.rehire_id=r.id and d.reviewer=:actor and d.idempotency_key=:key)"
            : " and exists(select 1 from hr_rehire q where q.tenant=r.tenant and q.id=r.id and q.actor=:actor and q.idempotency_key=:key)";
    return db.sql(SELECT + where)
        .param("tenant", tenant)
        .param("actor", actor)
        .param("key", key)
        .query(Rehire.class)
        .optional();
  }

  public List<Episode> episodes(String tenant, String employee, LocalDate date) {
    return db.sql(
            "select 1 as number,e.start_date,(select x.inactive_date from hr_employment_end_current x where x.tenant=e.tenant and x.employee_id=e.id) inactive_date,case when e.start_date>:date then 'SCHEDULED' when exists(select 1 from hr_employment_end_current x where x.tenant=e.tenant and x.employee_id=e.id and x.inactive_date<=:date) then 'ENDED' else 'CURRENT' end as state,'ORIGINAL' as source,null::varchar as request_id,null::timestamptz as recorded_at from hr_employee e where e.tenant=:tenant and e.id=:employee union all select p.number,p.start_date,null::date,case when p.start_date>:date then 'SCHEDULED' when exists(select 1 from hr_rehire_completion c where c.tenant=p.tenant and c.rehire_id=p.rehire_id and c.effective_date<=:date) then 'ACTIVE' else 'ONBOARDING' end,'REHIRE',p.rehire_id,p.recorded_at from hr_employment_episode p where p.tenant=:tenant and p.employee_id=:employee order by number")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("date", date)
        .query(Episode.class)
        .list();
  }

  public boolean hasConflictingChanges(String tenant, String employee, LocalDate start) {
    return db.sql(
            "select exists(select 1 from hr_request where tenant=:tenant and employee_id=:employee and status='SUBMITTED') or exists(select 1 from hr_version where tenant=:tenant and employee_id=:employee and field in('title','department','location','managerId','positionCode') and effective_date>=:start) or exists(select 1 from hr_end_withdrawal_current where tenant=:tenant and employee_id=:employee and status='SUBMITTED') or exists(select 1 from hr_end_reschedule_current where tenant=:tenant and employee_id=:employee and status='SUBMITTED') or exists(select 1 from hr_exit_task_current where tenant=:tenant and (employee_id=:employee or assignee_id=:employee) and status='OPEN')")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("start", start)
        .query(Boolean.class)
        .single();
  }

  public void submit(String tenant, Rehire r, String key) {
    db.sql(
            "insert into hr_rehire(tenant,id,employee_id,start_date,title,department,location,manager_id,prior_inactive_date,actor,actor_employee_id,reason,input_version,recorded_at,correlation_id,idempotency_key) values(:tenant,:id,:employee,:start,:title,:department,:location,:manager,:prior,:actor,:actorEmployee,:reason,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("employee", r.employeeId())
        .param("start", r.startDate())
        .param("title", r.title())
        .param("department", r.department())
        .param("location", r.location())
        .param("manager", r.managerId())
        .param("prior", r.priorInactiveDate())
        .param("actor", r.actor())
        .param("actorEmployee", r.actorEmployeeId())
        .param("reason", r.reason())
        .param("version", r.inputVersion())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.correlationId())
        .param("key", key)
        .update();
  }

  public void review(String tenant, Rehire r, String key) {
    if (r.status().equals("APPROVED")) {
      int n =
          db.sql(
                  "update hr_employee set version=version+1 where tenant=:tenant and id=:employee and version=:version")
              .param("tenant", tenant)
              .param("employee", r.employeeId())
              .param("version", r.reviewVersion())
              .update();
      if (n != 1) throw new ChangeRequest.Conflict("Employee changed. Refresh context.");
    }
    db.sql(
            "insert into hr_rehire_decision(tenant,rehire_id,status,reviewer,reviewer_employee_id,comment,review_version,reviewed_at,review_correlation_id,idempotency_key) values(:tenant,:id,:status,:reviewer,:reviewerEmployee,:comment,:version,:now,:correlation,:key)")
        .param("tenant", tenant)
        .param("id", r.id())
        .param("status", r.status())
        .param("reviewer", r.reviewer())
        .param("reviewerEmployee", r.reviewerEmployeeId())
        .param("comment", r.comment())
        .param("version", r.reviewVersion())
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .param("correlation", r.reviewCorrelationId())
        .param("key", key)
        .update();
    if (r.status().equals("APPROVED")) {
      db.sql(
              "insert into hr_employment_episode(tenant,employee_id,number,rehire_id,start_date,prior_inactive_date,status,recorded_at,correlation_id) values(:tenant,:employee,2,:id,:start,:prior,'Onboarding',:now,:correlation)")
          .param("tenant", tenant)
          .param("employee", r.employeeId())
          .param("id", r.id())
          .param("start", r.startDate())
          .param("prior", r.priorInactiveDate())
          .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
          .param("correlation", r.reviewCorrelationId())
          .update();
      for (var target : List.of("ONBOARDING", "PAYROLL", "IDENTITY_REVIEW"))
        db.sql(
                "insert into hr_rehire_handoff(tenant,rehire_id,target,start_date,recorded_at,correlation_id,status) values(:tenant,:id,:target,:start,:now,:correlation,'PENDING')")
            .param("tenant", tenant)
            .param("id", r.id())
            .param("target", target)
            .param("start", r.startDate())
            .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
            .param("correlation", r.reviewCorrelationId())
            .update();
    }
    db.sql(
            "insert into hr_notification(tenant,subject,message,recorded_at) select :tenant,subject,:message,:now from identity_membership where tenant=:tenant and role='HR' and employee_id<>:employee")
        .param("tenant", tenant)
        .param("employee", r.employeeId())
        .param(
            "message",
            r.status().equals("APPROVED")
                ? "A rehire was approved into onboarding. Legacy access remains blocked; onboarding, payroll and identity reviews remain pending."
                : "A rehire request was rejected; prior employment and access remain unchanged.")
        .param("now", r.reviewedAt().atOffset(ZoneOffset.UTC))
        .update();
  }
}
