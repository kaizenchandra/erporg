package com.atlas.modules.documents.configuration;

import com.atlas.modules.corehr.api.WorkforceAccess;
import com.atlas.modules.documents.adapter.out.integration.CoreHrDocumentIdentity;
import com.atlas.modules.documents.adapter.out.persistence.JdbcDocumentStore;
import com.atlas.modules.documents.adapter.out.scanning.ClamAvScanner;
import com.atlas.modules.documents.adapter.out.storage.LocalPrivateObjects;
import com.atlas.modules.documents.application.port.in.DocumentsUseCases;
import com.atlas.modules.documents.application.port.out.UnitOfWork;
import com.atlas.modules.documents.application.usecase.DocumentsService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("local | oidc-demo")
public class DocumentsConfiguration {
  @Bean
  com.atlas.modules.documents.application.port.in.DocumentRetentionUseCases documentRetention(
      JdbcClient db, PlatformTransactionManager manager, WorkforceAccess access) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new com.atlas.modules.documents.application.usecase.DocumentRetentionService(
        new com.atlas.modules.documents.adapter.out.persistence.JdbcDocumentRetentionStore(db),
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(access),
        tx,
        Clock.systemUTC());
  }

  @Bean
  com.atlas.modules.documents.application.port.in.DocumentMaintenanceUseCases documentMaintenance(
      JdbcClient db, PlatformTransactionManager manager, WorkforceAccess access) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new com.atlas.modules.documents.application.usecase.DocumentMaintenanceService(
        new com.atlas.modules.documents.adapter.out.persistence.JdbcDocumentTemplateStore(db),
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(access),
        tx,
        Clock.systemUTC());
  }

  @Bean
  com.atlas.modules.documents.application.port.in.DocumentTemplatesUseCases documentTemplates(
      JdbcClient db, PlatformTransactionManager manager, WorkforceAccess access) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new com.atlas.modules.documents.application.usecase.DocumentTemplatesService(
        new com.atlas.modules.documents.adapter.out.persistence.JdbcDocumentTemplateStore(db),
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(access),
        tx,
        Clock.systemUTC());
  }

  @Bean
  DocumentsUseCases documents(
      JdbcClient db,
      PlatformTransactionManager manager,
      WorkforceAccess access,
      @Value("${atlas.documents.local-root:${user.dir}/.local/private-documents}") String root,
      @Value("${atlas.documents.scanner-host:127.0.0.1}") String host,
      @Value("${atlas.documents.scanner-port:3311}") int port) {
    var template = new TransactionTemplate(manager);
    template.setTimeout(10);
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> operation) {
            return template.execute(status -> operation.get());
          }
        };
    return new DocumentsService(
        new JdbcDocumentStore(db),
        new CoreHrDocumentIdentity(access),
        new LocalPrivateObjects(Path.of(root)),
        new ClamAvScanner(host, port),
        tx,
        Clock.systemUTC());
  }
}
