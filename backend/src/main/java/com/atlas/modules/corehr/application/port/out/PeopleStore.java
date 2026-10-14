package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.AuditEntry;
import com.atlas.modules.corehr.application.model.DirectoryPage;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.Notification;
import com.atlas.modules.corehr.application.model.PeopleReadScope;
import com.atlas.modules.corehr.application.model.PeopleSummary;
import com.atlas.modules.corehr.application.model.RequestPage;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.*;

public interface PeopleStore {
  DirectoryPage directory(PeopleReadScope scope, DirectoryQuery query, LocalDate asOf);

  PeopleSummary summary(PeopleReadScope scope);

  RequestPage requestPage(PeopleReadScope scope, RequestQuery query);

  Actor actor(String subject);

  List<Employee> employees(PeopleReadScope scope, LocalDate asOf);

  Employee employee(Actor actor, String id, LocalDate asOf, boolean lock);

  List<ChangeRequest> requests(PeopleReadScope scope);

  Optional<ChangeRequest> duplicate(Actor actor, String key);

  ChangeRequest request(Actor actor, String id, boolean lock);

  void insert(Actor actor, ChangeRequest request, String key, String correlation);

  boolean hasLaterChange(Actor actor, ChangeRequest request);

  void decide(
      Actor actor,
      ChangeRequest request,
      String decision,
      String comment,
      Instant now,
      String correlation);

  List<AuditEntry> history(Actor actor, String employeeId);

  List<Notification> notifications(Actor actor);
}
