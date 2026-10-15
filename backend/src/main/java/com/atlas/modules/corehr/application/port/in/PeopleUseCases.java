package com.atlas.modules.corehr.application.port.in;

import com.atlas.modules.corehr.application.model.AuditEntry;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.model.DirectoryPage;
import com.atlas.modules.corehr.application.model.DirectoryQuery;
import com.atlas.modules.corehr.application.model.Notification;
import com.atlas.modules.corehr.application.model.PeopleSummary;
import com.atlas.modules.corehr.application.model.RequestPage;
import com.atlas.modules.corehr.application.model.RequestQuery;
import com.atlas.modules.corehr.domain.model.*;
import java.util.*;

public interface PeopleUseCases {
  com.atlas.modules.corehr.application.model.ManagerPage managerCandidates(
      Actor actor, com.atlas.modules.corehr.application.model.ManagerQuery query);

  DirectoryPage directory(Actor actor, DirectoryQuery query);

  PeopleSummary summary(Actor actor);

  Employee profile(Actor actor, String id);

  RequestPage requestPage(Actor actor, RequestQuery query);

  Actor actor(String subject);

  List<Employee> employees(Actor actor);

  List<ChangeRequest> requests(Actor actor);

  ChangeRequest submit(Actor actor, Submit command, String correlation);

  ChangeRequest withdraw(Actor actor, String id, Withdraw command, String correlation);

  ChangeRequest decide(Actor actor, String id, Decide command, String correlation);

  List<AuditEntry> history(Actor actor, String employeeId);

  List<Notification> notifications(Actor actor);
}
