package com.atlas.modules.corehr.adapter.out.persistence;

import com.atlas.modules.corehr.application.port.out.TalentRoleStore;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcTalentRoleStore implements TalentRoleStore {
  private final JdbcClient db;

  public JdbcTalentRoleStore(JdbcClient db) {
    this.db = db;
  }

  public boolean hasRole(String t, String e, String r) {
    return db.sql(
            "select exists(select 1 from identity_membership where tenant=:t and employee_id=:e and role=:r)")
        .param("t", t)
        .param("e", e)
        .param("r", r)
        .query(Boolean.class)
        .single();
  }
}
