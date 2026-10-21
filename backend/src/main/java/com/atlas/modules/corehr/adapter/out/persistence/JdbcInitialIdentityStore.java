package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.model.InitialIdentityModels.*;
import com.atlas.modules.corehr.application.port.out.InitialIdentityStore;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public final class JdbcInitialIdentityStore implements InitialIdentityStore {
  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper();

  public JdbcInitialIdentityStore(JdbcClient d) {
    db = d;
  }

  public void lock(String s) {
    db.sql(
            "select pg_advisory_xact_lock(hashtextextended(current_schema()||'/initial-identity/'||:s,0))")
        .param("s", s)
        .query((r, n) -> 0)
        .single();
  }

  public boolean eligible(String t, String e, String s) {
    return db.sql(
            "select exists(select 1 from hr_hire_request where tenant=:t and employee_id=:e and status='APPROVED') and not exists(select 1 from identity_membership where subject=:s or (tenant=:t and employee_id=:e)) and not exists(select 1 from hr_employment_episode where tenant=:t and employee_id=:e) and not exists(select 1 from hr_local_access_grant where subject=:s) and not exists(select 1 from hr_rehire_access_revocation where subject=:s)")
        .param("t", t)
        .param("e", e)
        .param("s", s)
        .query(Boolean.class)
        .single();
  }

  public Optional<Request> replay(String t, String s, String k) {
    return db.sql(
            "select snapshot::text from hr_initial_identity_request where tenant=:t and prepared_by=:s and command_key=:k")
        .param("t", t)
        .param("s", s)
        .param("k", k)
        .query((r, n) -> json.readValue(r.getString(1), Request.class))
        .optional();
  }

  public Optional<Request> find(String t, String id) {
    return db.sql(
            "select snapshot::text from hr_initial_identity_request where tenant=:t and id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> json.readValue(r.getString(1), Request.class))
        .optional();
  }

  public Optional<Decision> decision(String t, String id) {
    return db.sql(
            "select snapshot::text from hr_initial_identity_decision where tenant=:t and request_id=:id")
        .param("t", t)
        .param("id", id)
        .query((r, n) -> json.readValue(r.getString(1), Decision.class))
        .optional();
  }

  public List<Binding> bindings(String t, String e) {
    return db.sql(
            "select r.snapshot::text,d.snapshot::text from hr_initial_identity_request r left join hr_initial_identity_decision d on d.tenant=r.tenant and d.request_id=r.id where r.tenant=:t and r.employee_id=:e order by (r.snapshot->>'recordedAt')::timestamptz desc,r.id desc limit 20")
        .param("t", t)
        .param("e", e)
        .query(
            (r, n) ->
                new Binding(
                    json.readValue(r.getString(1), Request.class),
                    r.getString(2) == null ? null : json.readValue(r.getString(2), Decision.class)))
        .list();
  }

  public Optional<String> issuer(String s) {
    return db.sql(
            "select r.snapshot->>'issuer' from hr_initial_identity_request r join hr_initial_identity_decision d on d.tenant=r.tenant and d.request_id=r.id where r.snapshot->>'federatedSubject'=:s and d.snapshot->>'decision'='APPROVE'")
        .param("s", s)
        .query(String.class)
        .optional();
  }

  public void insert(String t, Request r) {
    db.sql(
            "insert into hr_initial_identity_request(tenant,id,employee_id,prepared_by,command_key,snapshot) values(:t,:id,:e,:a,:k,cast(:v as jsonb))")
        .param("t", t)
        .param("id", r.id())
        .param("e", r.employeeId())
        .param("a", r.preparedBy())
        .param("k", r.idempotencyKey())
        .param("v", json.writeValueAsString(r))
        .update();
  }

  public void decide(String t, Request r, Decision d, String name) {
    db.sql(
            "insert into hr_initial_identity_decision(tenant,request_id,snapshot) values(:t,:id,cast(:v as jsonb))")
        .param("t", t)
        .param("id", r.id())
        .param("v", json.writeValueAsString(d))
        .update();
    if (d.decision().equals("APPROVE"))
      db.sql(
              "insert into identity_membership(subject,tenant,employee_id,name,role) values(:s,:t,:e,:n,'EMPLOYEE')")
          .param("s", r.federatedSubject())
          .param("t", t)
          .param("e", r.employeeId())
          .param("n", name)
          .update();
  }
}
