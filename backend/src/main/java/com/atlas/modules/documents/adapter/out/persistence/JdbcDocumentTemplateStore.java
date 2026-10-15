package com.atlas.modules.documents.adapter.out.persistence;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.out.DocumentTemplateStore;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcDocumentTemplateStore implements DocumentTemplateStore {
  private final JdbcClient db;

  public JdbcDocumentTemplateStore(JdbcClient db) {
    this.db = db;
  }

  private DocumentTemplate template(ResultSet r, int n) throws SQLException {
    return new DocumentTemplate(
        r.getString("code"),
        r.getString("title"),
        r.getString("body"),
        r.getBoolean("required"),
        r.getDate("effective_date").toLocalDate(),
        r.getString("actor"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"),
        r.getInt("version"),
        r.getBoolean("retired"));
  }

  private DocumentEvidence evidence(ResultSet r, int n) throws SQLException {
    return new DocumentEvidence(
        r.getString("code"),
        r.getString("employee_id"),
        r.getString("root_id"),
        r.getInt("linked_version"),
        r.getString("actor"),
        r.getString("reason"),
        r.getTimestamp("recorded_at").toInstant(),
        r.getString("correlation_id"),
        r.getInt("classification_version"),
        r.getString("previous_root_id"));
  }

  public void lock(String tenant) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "document-templates:" + tenant)
        .query(Object.class)
        .single();
  }

  public List<DocumentTemplate> templates(String tenant, java.time.LocalDate asOf) {
    return db.sql(
            "select p.* from ("
                + TEMPLATES
                + ") p where tenant=:t and not exists(select 1 from document_template_revision n where n.tenant=p.tenant and n.code=p.code and n.effective_date<=:asOf and n.version>p.version) and (p.version=1 or p.effective_date<=:asOf) order by code limit 101")
        .param("t", tenant)
        .param("asOf", asOf)
        .query(this::template)
        .list();
  }

  public Optional<DocumentTemplate> templateDuplicate(String tenant, String actor, String key) {
    return db.sql(
            "select *,1 as version,false as retired from document_template where tenant=:t and actor=:a and idempotency_key=:k")
        .param("t", tenant)
        .param("a", actor)
        .param("k", key)
        .query(this::template)
        .optional();
  }

  public void create(String tenant, DocumentTemplate d, String key) {
    db.sql(
            "insert into document_template(tenant,code,title,body,required,effective_date,actor,reason,recorded_at,correlation_id,idempotency_key) values(:t,:c,:title,:body,:required,:date,:a,:r,:at,:corr,:k)")
        .param("t", tenant)
        .param("c", d.code())
        .param("title", d.title())
        .param("body", d.body())
        .param("required", d.required())
        .param("date", d.effectiveDate())
        .param("a", d.actor())
        .param("r", d.reason())
        .param("at", Timestamp.from(d.recordedAt()))
        .param("corr", d.correlationId())
        .param("k", key)
        .update();
  }

  public Optional<DocumentEvidence> linkDuplicate(String tenant, String actor, String key) {
    return db.sql(
            "select *,1 as classification_version,null::varchar as previous_root_id from document_template_evidence where tenant=:t and actor=:a and idempotency_key=:k")
        .param("t", tenant)
        .param("a", actor)
        .param("k", key)
        .query(this::evidence)
        .optional();
  }

  public void link(String tenant, DocumentEvidence d, String key) {
    db.sql(
            "insert into document_template_evidence(tenant,code,employee_id,root_id,linked_version,actor,reason,recorded_at,correlation_id,idempotency_key) values(:t,:c,:e,:root,:v,:a,:r,:at,:corr,:k)")
        .param("t", tenant)
        .param("c", d.code())
        .param("e", d.employeeId())
        .param("root", d.rootId())
        .param("v", d.linkedVersion())
        .param("a", d.actor())
        .param("r", d.reason())
        .param("at", Timestamp.from(d.recordedAt()))
        .param("corr", d.correlationId())
        .param("k", key)
        .update();
  }

  public List<DocumentEvidence> evidence(String tenant, String employeeId) {
    return db.sql(
            "select p.* from ("
                + EVIDENCE
                + ") p where tenant=:t and employee_id=:e and not exists(select 1 from document_template_reclassification n where n.tenant=p.tenant and n.code=p.code and n.employee_id=p.employee_id and n.classification_version>p.classification_version) order by code limit 101")
        .param("t", tenant)
        .param("e", employeeId)
        .query(this::evidence)
        .list();
  }

  private static final String TEMPLATES =
      "select tenant,code,title,body,required,effective_date,actor,reason,recorded_at,correlation_id,1 as version,false as retired from document_template union all select tenant,code,title,body,required,effective_date,actor,reason,recorded_at,correlation_id,version,retired from document_template_revision";
  private static final String EVIDENCE =
      "select tenant,code,employee_id,root_id,linked_version,actor,reason,recorded_at,correlation_id,1 as classification_version,null::varchar as previous_root_id from document_template_evidence union all select tenant,code,employee_id,root_id,linked_version,actor,reason,recorded_at,correlation_id,classification_version,previous_root_id from document_template_reclassification";

  public List<DocumentTemplate> revisions(String tenant, String code) {
    return db.sql(
            "select * from ("
                + TEMPLATES
                + ") p where tenant=:t and code=:c order by version desc limit 100")
        .param("t", tenant)
        .param("c", code)
        .query(this::template)
        .list();
  }

  public Optional<DocumentTemplate> revisionDuplicate(String tenant, String actor, String key) {
    return db.sql(
            "select * from document_template_revision where tenant=:t and actor=:a and idempotency_key=:k")
        .param("t", tenant)
        .param("a", actor)
        .param("k", key)
        .query(this::template)
        .optional();
  }

  public void revise(String tenant, DocumentTemplate d, String key) {
    db.sql(
            "insert into document_template_revision(tenant,code,version,title,body,required,effective_date,retired,actor,reason,recorded_at,correlation_id,idempotency_key) values(:t,:c,:v,:title,:body,:required,:date,:retired,:a,:r,:at,:corr,:k)")
        .param("t", tenant)
        .param("c", d.code())
        .param("v", d.version())
        .param("title", d.title())
        .param("body", d.body())
        .param("required", d.required())
        .param("date", d.effectiveDate())
        .param("retired", d.retired())
        .param("a", d.actor())
        .param("r", d.reason())
        .param("at", Timestamp.from(d.recordedAt()))
        .param("corr", d.correlationId())
        .param("k", key)
        .update();
  }

  public Optional<DocumentEvidence> reclassificationDuplicate(
      String tenant, String actor, String key) {
    return db.sql(
            "select * from document_template_reclassification where tenant=:t and actor=:a and idempotency_key=:k")
        .param("t", tenant)
        .param("a", actor)
        .param("k", key)
        .query(this::evidence)
        .optional();
  }

  public void reclassify(String tenant, DocumentEvidence d, String key) {
    db.sql(
            "insert into document_template_reclassification(tenant,code,employee_id,classification_version,previous_root_id,root_id,linked_version,actor,reason,recorded_at,correlation_id,idempotency_key) values(:t,:c,:e,:v,:old,:root,:file,:a,:r,:at,:corr,:k)")
        .param("t", tenant)
        .param("c", d.code())
        .param("e", d.employeeId())
        .param("v", d.classificationVersion())
        .param("old", d.previousRootId())
        .param("root", d.rootId())
        .param("file", d.linkedVersion())
        .param("a", d.actor())
        .param("r", d.reason())
        .param("at", Timestamp.from(d.recordedAt()))
        .param("corr", d.correlationId())
        .param("k", key)
        .update();
  }

  public List<DocumentEvidence> evidenceHistory(String tenant, String employeeId, String code) {
    return db.sql(
            "select * from ("
                + EVIDENCE
                + ") p where tenant=:t and employee_id=:e and (cast(:c as varchar) is null or code=:c) order by recorded_at desc,code,classification_version desc limit 100")
        .param("t", tenant)
        .param("e", employeeId)
        .param("c", code)
        .query(this::evidence)
        .list();
  }

  public boolean reserved(String tenant, String rootId) {
    return db.sql(
            "select exists(select 1 from (" + EVIDENCE + ") p where tenant=:t and root_id=:root)")
        .param("t", tenant)
        .param("root", rootId)
        .query(Boolean.class)
        .single();
  }
}
