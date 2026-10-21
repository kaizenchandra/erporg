package com.atlas.modules.corehr.application.port.in;

public interface TalentRoleUseCases {
  java.util.List<String> hrReviewers(String subject);

  boolean hasRole(String subject, String employee, String role);
}
