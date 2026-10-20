package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.ChangeRequest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5438/atlas?currentSchema=atlas_test",
      "spring.flyway.default-schema=atlas_test",
      "spring.flyway.schemas=atlas_test"
    })
@ActiveProfiles("local")
@Transactional
class DepartmentSelectionWorkflowTest {
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired DepartmentDirectoryUseCases directory;
  @Autowired JdbcTemplate db;

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  void fixture() {
    db.update(
        "insert into hr_department(tenant,code,name,cost_centre,effective_from,created_by,idempotency_key) select 'atlas','BB-'||lpad(g::text,4,'0'),'ZZZ BB selection '||lpad(g::text,4,'0'),'DEMO-BB',date '2020-01-01','fixture','bb-selection-'||g from generate_series(0,1004)g");
  }

  @Test
  void selectBeyond1000ThroughRealTransferApprovalAndPositionCommand() {
    fixture();
    var hr = people.actor("amelia");
    var reviewer = people.actor("olivia");
    assertFalse(organisation.departments(hr).stream().anyMatch(d -> d.code().equals("BB-1004")));
    var choice =
        directory
            .browse(
                hr, new DepartmentDirectoryModels.Query("BB-1004", "ALL", "ALL", "", 0, 8, "asc"))
            .items()
            .getFirst();
    var employee = people.profile(hr, "E003");
    var request =
        people.submit(
            hr,
            new Submit(
                "E003",
                "department",
                choice.name(),
                today(),
                "Synthetic full reference transfer",
                employee.version(),
                UUID.randomUUID().toString()),
            "bb-transfer");
    people.decide(
        reviewer,
        request.id(),
        new Decide("APPROVED", "Independent reference transfer"),
        "bb-transfer-decision");
    assertEquals(choice.name(), people.profile(hr, "E003").department());
    var command =
        new CreatePosition(
            "BB-POSITION",
            "Synthetic BB position",
            choice.code(),
            today(),
            "Synthetic selected reference",
            UUID.randomUUID().toString());
    var created = organisation.createPosition(hr, command, "bb-position");
    assertEquals(created, organisation.createPosition(hr, command, "bb-replay"));
    var listing =
        organisation.positions(hr, new PositionQuery("BB-POSITION", choice.code(), 0, 8, "asc"));
    assertEquals(1, listing.total());
    assertEquals(choice.name(), listing.items().getFirst().departmentName());
    assertEquals(created.departmentCode(), listing.items().getFirst().departmentCode());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from hr_position_audit where tenant='atlas' and code='BB-POSITION'",
            Integer.class));
  }

  @Test
  void plannedChoiceDoesNotBypassBusinessDateValidation() {
    var hr = people.actor("amelia");
    var future = today().plusDays(2);
    organisation.create(
        hr,
        new CreateDepartment(
            "BB-FUTURE",
            "BB Future selection",
            null,
            "DEMO-BB",
            future,
            "Synthetic future reference",
            UUID.randomUUID().toString()),
        "bb-future");
    assertEquals(
        1,
        directory
            .browse(
                hr, new DepartmentDirectoryModels.Query("BB-FUTURE", "ALL", "ALL", "", 0, 8, "asc"))
            .total());
    var employee = people.profile(hr, "E003");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            people.submit(
                hr,
                new Submit(
                    "E003",
                    "department",
                    "BB Future selection",
                    today(),
                    "Invalid early date",
                    employee.version(),
                    UUID.randomUUID().toString()),
                "bb-early"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            organisation.createPosition(
                hr,
                new CreatePosition(
                    "BB-EARLY",
                    "Early position",
                    "BB-FUTURE",
                    today(),
                    "Invalid early date",
                    UUID.randomUUID().toString()),
                "bb-early-position"));
    var request =
        people.submit(
            hr,
            new Submit(
                "E003",
                "department",
                "BB Future selection",
                future,
                "Future transfer",
                employee.version(),
                UUID.randomUUID().toString()),
            "bb-future-transfer");
    people.decide(
        people.actor("olivia"),
        request.id(),
        new Decide("APPROVED", "Valid future reference"),
        "bb-future-review");
    assertEquals(employee.department(), people.profile(hr, "E003").department());
  }

  @Test
  void namesAndPositionListingRemainTenantScoped() {
    var hr = people.actor("amelia");
    var other = people.actor("outsider");
    organisation.create(
        hr,
        new CreateDepartment(
            "BB-TENANT",
            "Atlas BB department",
            null,
            "DEMO-BB",
            today(),
            "Synthetic tenant reference",
            UUID.randomUUID().toString()),
        "bb-atlas");
    db.update(
        "insert into hr_department(tenant,code,name,cost_centre,effective_from,created_by,idempotency_key) values('other','BB-TENANT','Other BB department','DEMO-BB',current_date,'fixture','bb-other')");
    organisation.createPosition(
        hr,
        new CreatePosition(
            "BB-TENANT-POS",
            "Tenant position",
            "BB-TENANT",
            today(),
            "Synthetic position",
            UUID.randomUUID().toString()),
        "bb-tenant-position");
    assertEquals(
        "Atlas BB department",
        organisation
            .positions(hr, new PositionQuery("BB-TENANT-POS", "", 0, 8, "asc"))
            .items()
            .getFirst()
            .departmentName());
    assertEquals(
        0,
        organisation.positions(other, new PositionQuery("BB-TENANT-POS", "", 0, 8, "asc")).total());
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            organisation.createPosition(
                other,
                new CreatePosition(
                    "BB-FORBIDDEN",
                    "Forbidden",
                    "BB-TENANT",
                    today(),
                    "No privilege",
                    UUID.randomUUID().toString()),
                "bb-forbidden"));
  }
}
