package com.atlas.modules.corehr.configuration;

import com.atlas.modules.corehr.adapter.in.integration.TalentWorkforceAdapter;
import com.atlas.modules.corehr.adapter.out.persistence.JdbcTalentRoleStore;
import com.atlas.modules.corehr.api.TalentWorkforceAccess;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.usecase.TalentRoleService;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class TalentReferenceConfiguration {
  @Bean
  TalentRoleUseCases talentRoles(PeopleUseCases p, JdbcClient db) {
    return new TalentRoleService(p, new JdbcTalentRoleStore(db));
  }

  @Bean
  TalentWorkforceAccess talentWorkforce(PeopleUseCases p, HiringUseCases h, TalentRoleUseCases r) {
    return new TalentWorkforceAdapter(p, h, r);
  }
}
