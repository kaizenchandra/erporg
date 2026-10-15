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
  void lockReportingGraph(String tenant);

  List<ReportingLink> reportingLinks(String tenant, LocalDate date, int limit);

  List<LocalDate> reportingDates(String tenant, LocalDate from, int limit);

  List<String> managerEmployeeIds(String tenant, List<String> roles);

  com.atlas.modules.corehr.application.model.ManagerPage managerCandidates(
      String tenant,
      com.atlas.modules.corehr.application.model.ManagerQuery query,
      List<String> roles,
      LocalDate asOf);

  DirectoryPage directory(PeopleReadScope scope, DirectoryQuery query, LocalDate asOf);

  PeopleSummary summary(PeopleReadScope scope, LocalDate asOf);

  RequestPage requestPage(PeopleReadScope scope, RequestQuery query, LocalDate asOf);

  Actor actor(String subject, LocalDate asOf);

  List<Employee> employees(PeopleReadScope scope, LocalDate asOf);

  Employee employee(Actor actor, String id, LocalDate asOf, boolean lock);

  List<ChangeRequest> requests(PeopleReadScope scope, LocalDate asOf);

  Optional<ChangeRequest> duplicate(Actor actor, String key, LocalDate asOf);

  ChangeRequest request(Actor actor, String id, boolean lock, LocalDate asOf);

  void insert(Actor actor, ChangeRequest request, String key, String correlation);

  List<LocalDate> employmentChangeDates(Actor actor, String employeeId, LocalDate from);

  boolean hasLaterChange(Actor actor, ChangeRequest request);

  Optional<com.atlas.modules.corehr.application.model.Withdrawal> withdrawalReplay(
      Actor actor, String key);

  void withdraw(
      Actor actor,
      ChangeRequest request,
      com.atlas.modules.corehr.application.model.Withdrawal withdrawal);

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
