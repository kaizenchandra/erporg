package com.atlas.modules.documents.adapter.out.persistence;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.out.DocumentStore;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcDocumentStore implements DocumentStore {
  private final JdbcClient db;

  private static final String SELECT =
      "select d.*,coalesce(s.status,d.status) effective_status,coalesce(s.scanner,d.scanner) effective_scanner,coalesce(s.attempt,0) scan_attempts,coalesce(v.root_id,d.id) root_id,coalesce(v.version,1) document_version from employee_document d left join employee_document_version v on v.tenant=d.tenant and v.document_id=d.id left join lateral (select status,scanner,attempt from employee_document_scan_retry r where r.tenant=d.tenant and r.document_id=d.id order by attempt desc limit 1)s on true";

  public JdbcDocumentStore(JdbcClient db) {
    this.db = db;
  }

  public void lock(String tenant, String employeeId) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "documents:" + tenant + ":" + employeeId)
        .query((r, n) -> 0)
        .single();
  }

  private StoredDocument row(java.sql.ResultSet r) throws java.sql.SQLException {
    return new StoredDocument(
        new DocumentView(
            r.getString("id"),
            r.getString("employee_id"),
            r.getString("filename"),
            r.getString("media_type"),
            r.getInt("bytes"),
            r.getString("effective_status"),
            r.getString("effective_scanner"),
            r.getString("reason"),
            r.getTimestamp("recorded_at").toInstant(),
            r.getInt("scan_attempts"),
            r.getString("root_id"),
            r.getInt("document_version")),
        r.getString("object_key"),
        r.getString("sha256"),
        r.getString("requested_by"),
        r.getString("idempotency_key"));
  }

  public Optional<StoredDocument> duplicate(String tenant, String subject, String key) {
    return db.sql(SELECT + " where d.tenant=:t and d.requested_by=:s and d.idempotency_key=:k")
        .param("t", tenant)
        .param("s", subject)
        .param("k", key)
        .query((r, n) -> row(r))
        .optional();
  }

  public Optional<StoredDocument> find(String tenant, String id) {
    return db.sql(SELECT + " where d.tenant=:t and d.id=:id")
        .param("t", tenant)
        .param("id", id)
        .query((r, n) -> row(r))
        .optional();
  }

  public Optional<StoredDocument> locked(String tenant, String id) {
    var found =
        db.sql("select id from employee_document where tenant=:t and id=:id for update")
            .param("t", tenant)
            .param("id", id)
            .query(String.class)
            .optional();
    return found.flatMap(i -> find(tenant, i));
  }

  public Optional<DocumentScanRetry> retryDuplicate(
      String tenant, String id, String actor, String key) {
    return db.sql(
            "select attempt,actor,reason,idempotency_key,expected_attempt,status,scanner,recorded_at,correlation_id from employee_document_scan_retry where tenant=:t and document_id=:id and actor=:actor and idempotency_key=:key")
        .param("t", tenant)
        .param("id", id)
        .param("actor", actor)
        .param("key", key)
        .query(DocumentScanRetry.class)
        .optional();
  }

  public void retry(String tenant, String id, DocumentScanRetry r, String hash) {
    db.sql(
            "insert into employee_document_scan_retry(tenant,document_id,attempt,actor,reason,idempotency_key,expected_attempt,status,scanner,recorded_at,correlation_id,scanned_sha256) values(:t,:id,:attempt,:actor,:reason,:key,:expected,:status,:scanner,:now,:corr,:hash)")
        .param("t", tenant)
        .param("id", id)
        .param("attempt", r.attempt())
        .param("actor", r.actor())
        .param("reason", r.reason())
        .param("key", r.idempotencyKey())
        .param("expected", r.expectedAttempt())
        .param("status", r.status())
        .param("scanner", r.scanner())
        .param("now", r.recordedAt().atOffset(ZoneOffset.UTC))
        .param("corr", r.correlationId())
        .param("hash", hash)
        .update();
  }

  public List<DocumentView> versions(String tenant, String rootId) {
    return db.sql(
            SELECT
                + " where d.tenant=:t and (d.id=:root or v.root_id=:root) order by coalesce(v.version,1) desc limit 100")
        .param("t", tenant)
        .param("root", rootId)
        .query((r, n) -> row(r).view())
        .list();
  }

  public Optional<String> parent(String tenant, String id) {
    return db.sql(
            "select parent_id from employee_document_version where tenant=:t and document_id=:id")
        .param("t", tenant)
        .param("id", id)
        .query(String.class)
        .optional();
  }

  public void linkVersion(String tenant, String parent, StoredDocument d, String correlation) {
    var v = d.view();
    db.sql(
            "insert into employee_document_version(tenant,root_id,parent_id,document_id,version,actor,reason,recorded_at,correlation_id) values(:t,:root,:parent,:id,:version,:actor,:reason,:now,:corr)")
        .param("t", tenant)
        .param("root", v.rootId())
        .param("parent", parent)
        .param("id", v.id())
        .param("version", v.version())
        .param("actor", d.requestedBy())
        .param("reason", v.reason())
        .param("now", v.recordedAt().atOffset(ZoneOffset.UTC))
        .param("corr", correlation)
        .update();
  }

  public long employeeCount(String tenant, String employeeId) {
    return db.sql("select count(*) from employee_document where tenant=:t and employee_id=:e")
        .param("t", tenant)
        .param("e", employeeId)
        .query(Long.class)
        .single();
  }

  public void insert(String tenant, StoredDocument d, String correlation) {
    var v = d.view();
    db.sql(
            "insert into employee_document(tenant,id,employee_id,filename,media_type,bytes,status,scanner,reason,recorded_at,object_key,sha256,requested_by,idempotency_key) values(:t,:id,:e,:name,:type,:bytes,:status,:scanner,:reason,:now,:object,:hash,:actor,:key)")
        .param("t", tenant)
        .param("id", v.id())
        .param("e", v.employeeId())
        .param("name", v.filename())
        .param("type", v.mediaType())
        .param("bytes", v.bytes())
        .param("status", v.status())
        .param("scanner", v.scanner())
        .param("reason", v.reason())
        .param("now", v.recordedAt().atOffset(ZoneOffset.UTC))
        .param("object", d.objectKey())
        .param("hash", d.sha256())
        .param("actor", d.requestedBy())
        .param("key", d.idempotencyKey())
        .update();
    audit(tenant, v.id(), "UPLOADED", d.requestedBy(), v.recordedAt(), v.reason(), correlation);
    audit(
        tenant,
        v.id(),
        v.status(),
        d.requestedBy(),
        v.recordedAt(),
        "Malware scan result: " + v.status(),
        correlation);
  }

  private void audit(
      String tenant,
      String id,
      String action,
      String actor,
      Instant now,
      String reason,
      String correlation) {
    db.sql(
            "insert into employee_document_audit(tenant,document_id,action,actor,recorded_at,reason,correlation_id) values(:t,:id,:action,:actor,:now,:reason,:corr)")
        .param("t", tenant)
        .param("id", id)
        .param("action", action)
        .param("actor", actor)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("reason", reason)
        .param("corr", correlation)
        .update();
  }

  public void downloaded(String tenant, String id, String actor, Instant now, String correlation) {
    audit(
        tenant,
        id,
        "DOWNLOAD_AUTHORISED",
        actor,
        now,
        "Authorised content retrieval; network receipt not confirmed",
        correlation);
  }

  public List<DocumentAudit> history(String tenant, String id) {
    return db.sql(
            "select action,actor,recorded_at,reason,correlation_id from (select action,actor,recorded_at,reason,correlation_id,id::text tie from employee_document_audit where tenant=:t and document_id=:id union all select 'RESCAN_'||status,actor,recorded_at,reason,correlation_id,'retry-'||attempt::text tie from employee_document_scan_retry where tenant=:t and document_id=:id union all select 'VERSION_UPLOADED',actor,recorded_at,reason,correlation_id,'version-'||version::text tie from employee_document_version where tenant=:t and document_id=:id)a order by recorded_at desc,tie desc limit 100")
        .param("t", tenant)
        .param("id", id)
        .query(DocumentAudit.class)
        .list();
  }

  public DocumentPage list(String tenant, String employeeId, String search, int page, int size) {
    String condition = employeeId == null ? "d.tenant=:t" : "d.tenant=:t and d.employee_id=:e";
    condition +=
        " and not exists(select 1 from employee_document_version newer where newer.tenant=d.tenant and newer.root_id=coalesce(v.root_id,d.id) and newer.version>coalesce(v.version,1))";
    condition += " and position(lower(:search) in lower(filename))>0";
    var rows =
        db.sql(SELECT + " where " + condition + " order by filename,id limit :size offset :offset")
            .param("t", tenant)
            .param("search", search)
            .param("size", size)
            .param("offset", (long) page * size);
    var count =
        db.sql(
                "select count(*) from employee_document d left join employee_document_version v on v.tenant=d.tenant and v.document_id=d.id where "
                    + condition)
            .param("t", tenant)
            .param("search", search);
    if (employeeId != null) {
      rows.param("e", employeeId);
      count.param("e", employeeId);
    }
    return new DocumentPage(
        rows.query((r, n) -> row(r).view()).list(), count.query(Long.class).single(), page, size);
  }
}
