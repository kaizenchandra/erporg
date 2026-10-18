package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public interface AdditionalContactsStore {
  record Current(List<EmergencyContact> contacts, LocalDate effectiveDate) {}

  Current current(String tenant, String employeeId, LocalDate date);

  record InboxScope(
      String tenant, String ownerEmployee, String excludeEmployee, String excludeSubject) {}

  Page inbox(InboxScope scope, Query query);

  Page page(String tenant, String employeeId, Query query);

  Optional<ContactRequest> duplicate(String tenant, String subject, String key, boolean decision);

  boolean pending(String tenant, String employeeId);

  ContactRequest get(String tenant, String employeeId, String id, boolean lock);

  void insert(String tenant, ContactRequest request, String key);

  void decide(
      String tenant,
      ContactRequest request,
      String reviewerEmployee,
      long reviewVersion,
      String key);
}
