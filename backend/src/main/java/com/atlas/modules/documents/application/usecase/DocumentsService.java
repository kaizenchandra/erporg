package com.atlas.modules.documents.application.usecase;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentsUseCases;
import com.atlas.modules.documents.application.port.out.*;
import com.atlas.modules.documents.domain.model.DocumentPolicy;
import com.atlas.shared.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

public final class DocumentsService implements DocumentsUseCases {
  private final DocumentStore store;
  private final DocumentIdentity identity;
  private final PrivateObjects objects;
  private final MalwareScanner scanner;
  private final UnitOfWork tx;
  private final Clock clock;

  public DocumentsService(
      DocumentStore store,
      DocumentIdentity identity,
      PrivateObjects objects,
      MalwareScanner scanner,
      UnitOfWork tx,
      Clock clock) {
    this.store = store;
    this.identity = identity;
    this.objects = objects;
    this.scanner = scanner;
    this.tx = tx;
    this.clock = clock;
  }

  private String hash(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required digest unavailable.");
    }
  }

  private void access(DocumentActor a, StoredDocument d) {
    if (!a.role().equals("HR") && !a.employeeId().equals(d.view().employeeId()))
      throw new AccessDenied();
  }

  private boolean same(StoredDocument d, DocumentActor a, UploadDocument c, String hash) {
    return d.view().version() == 1
        && d.view().rootId().equals(d.view().id())
        && d.view().employeeId().equals(a.employeeId())
        && d.sha256().equals(hash)
        && d.view().filename().equals(c.filename())
        && d.view().mediaType().equals(c.mediaType())
        && d.view().reason().equals(c.reason().trim());
  }

  private byte[] input(UploadDocument c) {
    if (c == null) throw new InputValidation(Map.of("file", "A replacement upload is required."));
    if (c.reason() == null
        || c.reason().isBlank()
        || c.reason().length() > 500
        || c.reason().chars().anyMatch(Character::isISOControl))
      throw new InputValidation(
          Map.of("reason", "Enter a reason of 1–500 characters without controls."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new InputValidation(Map.of("idempotencyKey", "A valid command key is required."));
    byte[] bytes;
    try {
      if (c.contentBase64() == null || c.contentBase64().length() > 21848)
        throw new IllegalArgumentException();
      bytes = Base64.getDecoder().decode(c.contentBase64());
    } catch (IllegalArgumentException e) {
      throw new InputValidation(Map.of("file", "Choose a valid text file of up to 16384 bytes."));
    }
    DocumentPolicy.validate(c.filename(), c.mediaType(), bytes);
    return bytes;
  }

  public DocumentView upload(String subject, UploadDocument c, String correlation) {
    var a = identity.actor(subject);
    var bytes = input(c);
    String digest = hash(bytes);
    var previous = store.duplicate(a.tenant(), a.subject(), c.idempotencyKey());
    if (previous.isPresent()) {
      access(a, previous.get());
      if (!same(previous.get(), a, c, digest))
        throw new CommandConflict("Upload key was used for different input.");
      return previous.get().view();
    }
    if (store.employeeCount(a.tenant(), a.employeeId()) >= 100)
      throw new CommandConflict("Document limit reached; contact HR operations.");
    String objectKey = hash(a.tenant().getBytes(StandardCharsets.UTF_8)) + "/" + digest;
    objects.put(objectKey, bytes);
    var scan = scanner.scan(bytes.clone());
    var status = DocumentPolicy.availability(scan.verdict());
    return tx.run(
        () -> {
          store.lock(a.tenant(), a.employeeId());
          var duplicate = store.duplicate(a.tenant(), a.subject(), c.idempotencyKey());
          if (duplicate.isPresent()) {
            access(a, duplicate.get());
            if (!same(duplicate.get(), a, c, digest))
              throw new CommandConflict("Upload key was used for different input.");
            return duplicate.get().view();
          }
          if (store.employeeCount(a.tenant(), a.employeeId()) >= 100)
            throw new CommandConflict("Document limit reached; contact HR operations.");
          var documentId = UUID.randomUUID().toString();
          var view =
              new DocumentView(
                  documentId,
                  a.employeeId(),
                  c.filename(),
                  c.mediaType(),
                  bytes.length,
                  status,
                  scan.scanner(),
                  c.reason().trim(),
                  clock.instant(),
                  0,
                  documentId,
                  1);
          store.insert(
              a.tenant(),
              new StoredDocument(view, objectKey, digest, a.subject(), c.idempotencyKey()),
              correlation);
          return view;
        });
  }

  private DocumentView retryView(StoredDocument d, DocumentScanRetry r) {
    var v = d.view();
    return new DocumentView(
        v.id(),
        v.employeeId(),
        v.filename(),
        v.mediaType(),
        v.bytes(),
        r.status(),
        r.scanner(),
        v.reason(),
        v.recordedAt(),
        r.attempt(),
        v.rootId(),
        v.version());
  }

  private Optional<DocumentView> retryReplay(
      DocumentActor a, StoredDocument d, RetryDocumentScan c) {
    var previous = store.retryDuplicate(a.tenant(), d.view().id(), a.subject(), c.idempotencyKey());
    if (previous.isEmpty()) return Optional.empty();
    var p = previous.get();
    if (p.expectedAttempt() != c.version() || !p.reason().equals(c.reason().trim()))
      throw new CommandConflict("Scan retry key was used for different input.");
    return Optional.of(retryView(d, p));
  }

  public DocumentView retry(String subject, String id, RetryDocumentScan c, String correlation) {
    var a = identity.actor(subject);
    if (!a.role().equals("HR")) throw new AccessDenied();
    if (c.reason() == null
        || c.reason().isBlank()
        || c.reason().length() > 500
        || c.reason().chars().anyMatch(Character::isISOControl))
      throw new InputValidation(
          Map.of("reason", "Enter a scan retry reason of 1–500 characters without controls."));
    if (c.idempotencyKey() == null || !c.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
      throw new InputValidation(Map.of("idempotencyKey", "A valid command key is required."));
    c.version();
    var d = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
    access(a, d);
    var replay = retryReplay(a, d, c);
    if (replay.isPresent()) return replay.get();
    DocumentPolicy.retry(d.view().status(), d.view().scanAttempts(), c.version());
    var bytes = objects.read(d.objectKey());
    if (bytes.length != d.view().bytes() || !hash(bytes).equals(d.sha256()))
      throw new CommandConflict("Stored document integrity check failed; contact HR operations.");
    DocumentPolicy.validate(d.view().filename(), d.view().mediaType(), bytes);
    var scan = scanner.scan(bytes.clone());
    var status = DocumentPolicy.availability(scan.verdict());
    return tx.run(
        () -> {
          if (!identity.actor(subject).equals(a)) throw new AccessDenied();
          var current = store.locked(a.tenant(), id).orElseThrow(AccessDenied::new);
          access(a, current);
          var duplicate = retryReplay(a, current, c);
          if (duplicate.isPresent()) return duplicate.get();
          DocumentPolicy.retry(current.view().status(), current.view().scanAttempts(), c.version());
          var attempt =
              new DocumentScanRetry(
                  current.view().scanAttempts() + 1,
                  a.subject(),
                  c.reason().trim(),
                  c.idempotencyKey(),
                  c.version(),
                  status,
                  scan.scanner(),
                  clock.instant(),
                  correlation);
          store.retry(a.tenant(), id, attempt, current.sha256());
          return retryView(current, attempt);
        });
  }

  public List<DocumentView> versions(String subject, String id) {
    var a = identity.actor(subject);
    var d = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
    access(a, d);
    return store.versions(a.tenant(), d.view().rootId());
  }

  private boolean versionSame(
      StoredDocument previous, StoredDocument parent, VersionDocument command, String digest) {
    var c = command.upload();
    return previous.view().employeeId().equals(parent.view().employeeId())
        && previous.view().rootId().equals(parent.view().rootId())
        && previous.view().version() == command.version() + 1
        && previous.sha256().equals(digest)
        && previous.view().filename().equals(c.filename())
        && previous.view().mediaType().equals(c.mediaType())
        && previous.view().reason().equals(c.reason().trim());
  }

  private Optional<DocumentView> versionReplay(
      DocumentActor a, StoredDocument parent, VersionDocument c, String digest) {
    var p = store.duplicate(a.tenant(), a.subject(), c.upload().idempotencyKey());
    if (p.isEmpty()) return Optional.empty();
    access(a, p.get());
    if (!versionSame(p.get(), parent, c, digest)
        || !store.parent(a.tenant(), p.get().view().id()).orElse("").equals(parent.view().id()))
      throw new CommandConflict("Upload key was used for different input.");
    return Optional.of(p.get().view());
  }

  private void versionCheck(DocumentActor a, StoredDocument parent, VersionDocument c) {
    var latest = store.versions(a.tenant(), parent.view().rootId()).getFirst();
    com.atlas.modules.documents.domain.model.DocumentVersionPolicy.append(
        parent.view().id(), latest.id(), latest.version(), c.version());
    if (store.employeeCount(a.tenant(), parent.view().employeeId()) >= 100)
      throw new CommandConflict("Document limit reached; contact HR operations.");
  }

  public DocumentView version(String subject, String id, VersionDocument c, String correlation) {
    var a = identity.actor(subject);
    var parent = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
    access(a, parent);
    c.version();
    var bytes = input(c.upload());
    var digest = hash(bytes);
    var replay = versionReplay(a, parent, c, digest);
    if (replay.isPresent()) return replay.get();
    versionCheck(a, parent, c);
    String objectKey = hash(a.tenant().getBytes(StandardCharsets.UTF_8)) + "/" + digest;
    objects.put(objectKey, bytes);
    var scan = scanner.scan(bytes.clone());
    var status = DocumentPolicy.availability(scan.verdict());
    return tx.run(
        () -> {
          if (!identity.actor(subject).equals(a)) throw new AccessDenied();
          store.lock(a.tenant(), parent.view().employeeId());
          store.locked(a.tenant(), parent.view().rootId()).orElseThrow(AccessDenied::new);
          var duplicate = versionReplay(a, parent, c, digest);
          if (duplicate.isPresent()) return duplicate.get();
          versionCheck(a, parent, c);
          var view =
              new DocumentView(
                  UUID.randomUUID().toString(),
                  parent.view().employeeId(),
                  c.upload().filename(),
                  c.upload().mediaType(),
                  bytes.length,
                  status,
                  scan.scanner(),
                  c.upload().reason().trim(),
                  clock.instant(),
                  0,
                  parent.view().rootId(),
                  c.version() + 1);
          var stored =
              new StoredDocument(view, objectKey, digest, a.subject(), c.upload().idempotencyKey());
          store.insert(a.tenant(), stored, correlation);
          store.linkVersion(a.tenant(), id, stored, correlation);
          return view;
        });
  }

  public DocumentPage list(String subject, String search, int page, int size) {
    var a = identity.actor(subject);
    if (search == null
        || search.length() > 120
        || page < 0
        || page > 100000
        || size < 1
        || size > 100) throw new IllegalArgumentException("Invalid document query.");
    return store.list(
        a.tenant(), a.role().equals("HR") ? null : a.employeeId(), search, page, size);
  }

  public DownloadDocument download(String subject, String id, String correlation) {
    var a = identity.actor(subject);
    var d = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
    access(a, d);
    if (!d.view().status().equals("AVAILABLE"))
      throw new CommandConflict(
          "Document is not available: scanning is required or the file was rejected.");
    var bytes = objects.read(d.objectKey());
    if (bytes.length != d.view().bytes() || !hash(bytes).equals(d.sha256()))
      throw new CommandConflict("Stored document integrity check failed; contact HR operations.");
    tx.run(
        () -> {
          store.downloaded(a.tenant(), id, a.subject(), clock.instant(), correlation);
          return null;
        });
    return new DownloadDocument(d.view().filename(), bytes);
  }

  public List<DocumentAudit> history(String subject, String id) {
    var a = identity.actor(subject);
    var d = store.find(a.tenant(), id).orElseThrow(AccessDenied::new);
    access(a, d);
    return store.history(a.tenant(), id);
  }
}
