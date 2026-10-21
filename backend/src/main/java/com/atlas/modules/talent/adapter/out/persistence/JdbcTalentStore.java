package com.atlas.modules.talent.adapter.out.persistence;

import com.atlas.modules.talent.application.model.ReminderModels;
import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.application.port.out.TalentPorts.Store;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import com.atlas.shared.domain.CommandConflict;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Owned snapshot persistence; workflow and access policies stay in the core. */
public final class JdbcTalentStore implements Store {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();
  private static final Map<String, String> TABLES =
      Map.of(
          "requisitions",
          "tal_requisition",
          "onboarding",
          "tal_onboarding",
          "reviews",
          "tal_review",
          "courses",
          "tal_course",
          "learning",
          "tal_enrollment",
          "compensation",
          "tal_compensation",
          "succession",
          "tal_succession",
          "scenarios",
          "tal_scenario");

  public JdbcTalentStore(JdbcClient db) {
    this.db = db;
  }

  private String table(String kind) {
    var t = TABLES.get(kind);
    if (t == null) throw new IllegalArgumentException("Unknown talent capability.");
    return t;
  }

  public boolean enrolled(String t, String c, String e) {
    return db.sql(
            "select exists(select 1 from tal_enrollment where tenant=:t and snapshot->>'courseId'=:c and snapshot->>'employeeId'=:e)")
        .param("t", t)
        .param("c", c)
        .param("e", e)
        .query(Boolean.class)
        .single();
  }

  public String encode(Object v) {
    return json.writeValueAsString(v);
  }

  public <T> T decode(String s, Class<T> type) {
    return json.readValue(s, type);
  }

  public void lock(String t) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(current_schema()||'/talent/'||:t,0))")
        .param("t", t)
        .query((r, n) -> 0)
        .single();
  }

  public <T> Optional<T> find(String t, String k, String id, Class<T> type) {
    return db.sql("select snapshot::text from " + table(k) + " where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), type))
        .optional();
  }

  public <T> Page<T> page(String t, String k, String employee, int page, Class<T> type) {
    var filter = " where tenant=:t and (:employee='' or snapshot->>'employeeId'=:employee)";
    var total =
        db.sql("select count(*) from " + table(k) + filter)
            .param("t", t)
            .param("employee", employee)
            .query(Long.class)
            .single();
    var items =
        db.sql(
                "select snapshot::text from "
                    + table(k)
                    + filter
                    + " order by (snapshot->'meta'->>'recordedAt')::timestamptz desc,id limit 10 offset :offset")
            .param("t", t)
            .param("employee", employee)
            .param("offset", page * 10)
            .query((r, n) -> decode(r.getString(1), type))
            .list();
    return new Page<>(items, total, page, 10);
  }

  public void insert(String t, String k, String id, Object s) {
    db.sql("insert into " + table(k) + "(tenant,id,snapshot) values(:t,:id,cast(:s as jsonb))")
        .param("t", t)
        .param("id", id)
        .param("s", encode(s))
        .update();
  }

  public void update(String t, String k, String id, long v, Object s) {
    if (db.sql(
                "update "
                    + table(k)
                    + " set snapshot=cast(:s as jsonb) where tenant=:t and id=:id and (snapshot->'meta'->>'version')::bigint=:v")
            .param("s", encode(s))
            .param("t", t)
            .param("id", id)
            .param("v", v)
            .update()
        != 1)
      throw new CommandConflict("Another user changed this record. Reload before trying again.");
  }

  public Optional<Receipt> receipt(String t, String s, String k) {
    return db.sql(
            "select fingerprint,result::text from tal_command where tenant=:t and subject=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((r, n) -> new Receipt(r.getString(1), r.getString(2)))
        .optional();
  }

  public void receipt(String t, String s, String k, String fp, Object r) {
    db.sql(
            "insert into tal_command(tenant,subject,command_key,fingerprint,result) values(:t,:s,:k,:fp,cast(:r as jsonb))")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .param("fp", fp)
        .param("r", encode(r))
        .update();
  }

  public List<Pending> approvalCandidates(String t, int page) {
    return db.sql(
            "select kind,id from (select 'requisitions' kind,id,snapshot from tal_requisition where tenant=:t union all select 'compensation',id,snapshot from tal_compensation where tenant=:t union all select 'reviews',id,snapshot from tal_review where tenant=:t) q where snapshot->'meta'->>'status'='SUBMITTED' order by (snapshot->'meta'->>'recordedAt')::timestamptz,id limit 20 offset :p")
        .param("t", t)
        .param("p", page * 20)
        .query((r, n) -> new Pending(r.getString(1), r.getString(2)))
        .list();
  }

  public Optional<Delegation> delegationReplay(String t, String a, String key) {
    return db.sql(
            "select snapshot::text from tal_delegation where tenant=:t and actor=:a and command_key=:k")
        .param("t", t)
        .param("a", a)
        .param("k", key)
        .query((r, n) -> decode(r.getString(1), Delegation.class))
        .optional();
  }

  public Optional<Delegation> delegation(String t, String id) {
    return db.sql("select snapshot::text from tal_delegation where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), Delegation.class))
        .optional();
  }

  public Optional<Delegation> activeDelegation(
      String t, String k, String id, long v, java.time.LocalDate date) {
    return db.sql(
            "select d.snapshot::text from tal_delegation d where d.tenant=:t and d.kind=:k and d.record_id=:id and d.record_version=:v and (d.snapshot->>'from')::date<=:date and (d.snapshot->>'through')::date>=:date and not exists(select 1 from tal_delegation_revocation r where r.tenant=d.tenant and r.delegation_id=d.id)")
        .param("t", t)
        .param("k", k)
        .param("id", id)
        .param("v", v)
        .param("date", date)
        .query((r, n) -> decode(r.getString(1), Delegation.class))
        .optional();
  }

  public boolean delegationOverlap(
      String t,
      String k,
      String id,
      long v,
      java.time.LocalDate from,
      java.time.LocalDate through) {
    return db.sql(
            "select exists(select 1 from tal_delegation d where d.tenant=:t and d.kind=:k and d.record_id=:id and d.record_version=:v and (d.snapshot->>'from')::date<=:through and (d.snapshot->>'through')::date>=:from and not exists(select 1 from tal_delegation_revocation r where r.tenant=d.tenant and r.delegation_id=d.id))")
        .param("t", t)
        .param("k", k)
        .param("id", id)
        .param("v", v)
        .param("from", from)
        .param("through", through)
        .query(Boolean.class)
        .single();
  }

  public void delegate(String t, Delegation d) {
    db.sql(
            "insert into tal_delegation(tenant,id,kind,record_id,record_version,actor,command_key,snapshot) values(:t,:id,:k,:r,:v,:a,:key,cast(:s as jsonb))")
        .param("t", t)
        .param("id", d.id())
        .param("k", d.kind())
        .param("r", d.recordId())
        .param("v", d.recordVersion())
        .param("a", d.actor())
        .param("key", d.commandKey())
        .param("s", encode(d))
        .update();
  }

  public Optional<DelegationRevocation> revocation(String t, String id) {
    return db.sql(
            "select snapshot::text from tal_delegation_revocation where tenant=:t and delegation_id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> decode(r.getString(1), DelegationRevocation.class))
        .optional();
  }

  public void revokeDelegation(String t, DelegationRevocation d) {
    db.sql(
            "insert into tal_delegation_revocation(tenant,delegation_id,snapshot) values(:t,:id,cast(:s as jsonb))")
        .param("t", t)
        .param("id", d.delegationId())
        .param("s", encode(d))
        .update();
  }

  public List<ResponsibilityHistory> delegationHistory(String t, String k, String id, int page) {
    return db.sql(
            "select d.snapshot::text,r.snapshot::text from tal_delegation d left join tal_delegation_revocation r on r.tenant=d.tenant and r.delegation_id=d.id where d.tenant=:t and d.kind=:k and d.record_id=:id order by (d.snapshot->>'recordedAt')::timestamptz desc,d.id desc limit 20 offset :p")
        .param("t", t)
        .param("k", k)
        .param("id", id)
        .param("p", page * 20)
        .query(
            (r, n) ->
                new ResponsibilityHistory(
                    decode(r.getString(1), Delegation.class),
                    r.getString(2) == null
                        ? null
                        : decode(r.getString(2), DelegationRevocation.class)))
        .list();
  }

  public Optional<ReminderModels.Policy> reminderPolicy(String t) {
    return db.sql(
            "select snapshot::text from tal_reminder_policy where tenant=:t order by version desc limit 1")
        .param("t", t)
        .query((r, n) -> decode(r.getString(1), ReminderModels.Policy.class))
        .optional();
  }

  public Optional<ReminderModels.State> reminderState(String t) {
    return db.sql("select snapshot::text from tal_reminder_state where tenant=:t")
        .param("t", t)
        .query((r, n) -> decode(r.getString(1), ReminderModels.State.class))
        .optional();
  }

  public void reminderPolicy(String t, ReminderModels.Policy p) {
    db.sql(
            "insert into tal_reminder_policy(tenant,id,version,snapshot) values(:t,:id,:v,cast(:s as jsonb))")
        .param("t", t)
        .param("id", p.id())
        .param("v", p.version())
        .param("s", encode(p))
        .update();
  }

  public void reminderState(String t, ReminderModels.State s) {
    int changed;
    if (s.version() == 0)
      changed =
          db.sql(
                  "insert into tal_reminder_state(tenant,policy_id,version,snapshot) values(:t,:p,:v,cast(:s as jsonb))")
              .param("t", t)
              .param("p", s.policyId())
              .param("v", s.version())
              .param("s", encode(s))
              .update();
    else
      changed =
          db.sql(
                  "update tal_reminder_state set policy_id=:p,version=:v,snapshot=cast(:s as jsonb) where tenant=:t and version=:old")
              .param("t", t)
              .param("p", s.policyId())
              .param("v", s.version())
              .param("old", s.version() - 1)
              .param("s", encode(s))
              .update();
    if (changed != 1) throw new CommandConflict("Reminder state changed. Reload.");
  }

  public Optional<ReminderModels.Due> reminderDue(java.time.Instant now) {
    return db.sql(
            "select s.tenant,p.snapshot::text from tal_reminder_state s join tal_reminder_policy p on p.tenant=s.tenant and p.id=s.policy_id where s.snapshot->>'status'='READY' and (p.snapshot->>'enabled')::boolean and (s.snapshot->>'nextDue')::timestamptz<=:now order by (s.snapshot->>'nextDue')::timestamptz,s.tenant limit 1")
        .param("now", java.sql.Timestamp.from(now))
        .query(
            (r, n) ->
                new ReminderModels.Due(
                    r.getString(1), decode(r.getString(2), ReminderModels.Policy.class)))
        .optional();
  }

  public List<ReminderModels.Source> reminderSources(
      String t, java.time.Instant cutoff, java.time.LocalDate day) {
    return db.sql(
            "select a.id,q.kind,q.id,(q.snapshot->'meta'->>'version')::bigint from (select 'requisitions' kind,id,snapshot from tal_requisition where tenant=:t union all select 'compensation',id,snapshot from tal_compensation where tenant=:t union all select 'reviews',id,snapshot from tal_review where tenant=:t) q join lateral(select a.id from tal_audit a where a.tenant=:t and a.kind=q.kind and a.record_id=q.id and ((a.snapshot->>'after')::jsonb->'meta'->>'version')::bigint=(q.snapshot->'meta'->>'version')::bigint order by a.id limit 1) a on true where q.snapshot->'meta'->>'status'='SUBMITTED' and (q.snapshot->'meta'->>'recordedAt')::timestamptz<=:cutoff and not exists(select 1 from tal_reminder_item i where i.tenant=:t and i.event_id=a.id and i.day=:day) order by (q.snapshot->'meta'->>'recordedAt')::timestamptz,q.id limit 20")
        .param("t", t)
        .param("cutoff", java.sql.Timestamp.from(cutoff))
        .param("day", day)
        .query(
            (r, n) ->
                new ReminderModels.Source(
                    r.getString(1), r.getString(2), r.getString(3), r.getLong(4)))
        .list();
  }

  public void reminderRun(String t, ReminderModels.Run r) {
    db.sql(
            "insert into tal_reminder_run(tenant,id,policy_id,snapshot) values(:t,:id,:p,cast(:s as jsonb))")
        .param("t", t)
        .param("id", r.id())
        .param("p", r.policyId())
        .param("s", encode(r))
        .update();
  }

  public void reminderItem(String t, ReminderModels.Item i) {
    db.sql(
            "insert into tal_reminder_item(tenant,id,run_id,event_id,day,snapshot) values(:t,:id,:r,:e,:d,cast(:s as jsonb))")
        .param("t", t)
        .param("id", i.id())
        .param("r", i.runId())
        .param("e", i.eventId())
        .param("d", i.day())
        .param("s", encode(i))
        .update();
  }

  public void reminderNotice(String t, Notice n, java.time.LocalDate day, String run) {
    db.sql(
            "insert into tal_reminder_notice(tenant,id,event_id,employee_id,day,run_id,snapshot) values(:t,:id,:e,:employee,:d,:r,cast(:s as jsonb))")
        .param("t", t)
        .param("id", n.id())
        .param("e", n.eventId())
        .param("employee", n.employeeId())
        .param("d", day)
        .param("r", run)
        .param("s", encode(n))
        .update();
  }

  public List<ReminderModels.Run> reminderRuns(String t, int page) {
    return db.sql(
            "select snapshot::text from tal_reminder_run where tenant=:t order by (snapshot->>'recordedAt')::timestamptz desc,id desc limit 20 offset :p")
        .param("t", t)
        .param("p", page * 20)
        .query((r, n) -> decode(r.getString(1), ReminderModels.Run.class))
        .list();
  }

  public List<ReminderModels.Item> reminderItems(String t, String run) {
    return db.sql(
            "select snapshot::text from tal_reminder_item where tenant=:t and run_id=:r order by id limit 20")
        .param("t", t)
        .param("r", run)
        .query((r, n) -> decode(r.getString(1), ReminderModels.Item.class))
        .list();
  }

  public void notice(String t, Notice n) {
    db.sql(
            "insert into tal_notice(tenant,id,event_id,employee_id,snapshot) values(:t,:id,:event,:e,cast(:s as jsonb))")
        .param("t", t)
        .param("id", n.id())
        .param("event", n.eventId())
        .param("e", n.employeeId())
        .param("s", encode(n))
        .update();
  }

  public List<Notice> notices(String t, String e, int page) {
    return db.sql(
            "select snapshot::text from (select id,snapshot from tal_notice where tenant=:t and employee_id=:e union all select id,snapshot from tal_reminder_notice where tenant=:t and employee_id=:e) n order by (snapshot->>'recordedAt')::timestamptz desc,id desc limit 20 offset :p")
        .param("t", t)
        .param("e", e)
        .param("p", page * 20)
        .query((r, n) -> decode(r.getString(1), Notice.class))
        .list();
  }

  public void exportReceipt(
      String t,
      String a,
      String e,
      String k,
      int page,
      int rows,
      String corr,
      java.time.Instant now) {
    db.sql(
            "insert into tal_export_receipt(tenant,id,actor,employee_id,kind,page,row_count,correlation_id,recorded_at) values(:t,:id,:a,:e,:k,:p,:rows,:c,:at)")
        .param("t", t)
        .param("id", java.util.UUID.randomUUID().toString())
        .param("a", a)
        .param("e", e)
        .param("k", k)
        .param("p", page)
        .param("rows", rows)
        .param("c", corr)
        .param("at", java.sql.Timestamp.from(now))
        .update();
  }

  public void audit(String t, Audit a) {
    db.sql(
            "insert into tal_audit(tenant,id,kind,record_id,snapshot) values(:t,:id,:k,:r,cast(:s as jsonb))")
        .param("t", t)
        .param("id", a.id())
        .param("k", a.kind())
        .param("r", a.recordId())
        .param("s", encode(a))
        .update();
  }

  public List<Audit> history(String t, String k, String id, int p) {
    return db.sql(
            "select snapshot::text from tal_audit where tenant=:t and kind=:k and record_id=:id order by ((snapshot->>'after')::jsonb->'meta'->>'version')::bigint,id limit 20 offset :p")
        .param("t", t)
        .param("k", k)
        .param("id", id)
        .param("p", p * 20)
        .query((r, n) -> decode(r.getString(1), Audit.class))
        .list();
  }
}
