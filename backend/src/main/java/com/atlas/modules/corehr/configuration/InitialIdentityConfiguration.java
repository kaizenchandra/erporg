package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.port.in.InitialIdentityUseCases;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.InitialIdentityService;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class InitialIdentityConfiguration {
  @Bean
  InitialIdentityUseCases initialIdentities(
      JdbcClient db,
      PlatformTransactionManager manager,
      Clock clock,
      @Value(
              "${INITIAL_IDENTITY_ISSUER:${spring.security.oauth2.client.provider.keycloak.issuer-uri:}}")
          String issuer) {
    var t = new TransactionTemplate(manager);
    t.setTimeout(10);
    return new InitialIdentityService(
        new JdbcInitialIdentityStore(db),
        new JdbcPeopleStore(db),
        new UnitOfWork() {
          public <T> T run(Supplier<T> f) {
            return t.execute(s -> f.get());
          }
        },
        clock,
        issuer);
  }
}
