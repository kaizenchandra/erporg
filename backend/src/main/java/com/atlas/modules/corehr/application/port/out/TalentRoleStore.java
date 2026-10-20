package com.atlas.modules.corehr.application.port.out;

public interface TalentRoleStore {
  boolean hasRole(String tenant, String employee, String role);
}
