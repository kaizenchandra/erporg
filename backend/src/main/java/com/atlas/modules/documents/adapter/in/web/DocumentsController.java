package com.atlas.modules.documents.adapter.in.web;

import com.atlas.modules.documents.application.model.*;
import com.atlas.modules.documents.application.port.in.DocumentsUseCases;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | oidc-demo")
@RequestMapping("/api/v1/documents")
public final class DocumentsController {
  private final DocumentsUseCases documents;

  public DocumentsController(DocumentsUseCases documents) {
    this.documents = documents;
  }

  @GetMapping
  DocumentPage list(
      Principal p,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "8") int size) {
    return documents.list(p.getName(), search, page, size);
  }

  @PostMapping
  DocumentView upload(
      Principal p,
      @RequestBody UploadDocument input,
      @RequestAttribute("correlationId") String correlation) {
    return documents.upload(p.getName(), input, correlation);
  }

  @GetMapping("/{id}/versions")
  List<DocumentView> versions(Principal p, @PathVariable String id) {
    return documents.versions(p.getName(), id);
  }

  @PostMapping("/{id}/versions")
  DocumentView version(
      Principal p,
      @PathVariable String id,
      @RequestBody VersionDocument input,
      @RequestAttribute("correlationId") String correlation) {
    return documents.version(p.getName(), id, input, correlation);
  }

  @PostMapping("/{id}/scan-retries")
  DocumentView retry(
      Principal p,
      @PathVariable String id,
      @RequestBody RetryDocumentScan input,
      @RequestAttribute("correlationId") String correlation) {
    return documents.retry(p.getName(), id, input, correlation);
  }

  @GetMapping("/{id}/history")
  List<DocumentAudit> history(Principal p, @PathVariable String id) {
    return documents.history(p.getName(), id);
  }

  @GetMapping("/{id}/content")
  ResponseEntity<byte[]> download(
      Principal p, @PathVariable String id, @RequestAttribute("correlationId") String correlation) {
    var d = documents.download(p.getName(), id, correlation);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(d.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(d.content());
  }
}
