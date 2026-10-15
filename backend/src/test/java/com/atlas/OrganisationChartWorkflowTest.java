package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.adapter.out.persistence.*;
import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.application.port.out.UnitOfWork;
import com.atlas.modules.corehr.application.usecase.OrganisationBrowseService;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class OrganisationChartWorkflowTest {
  @Autowired OrganisationBrowseUseCases chart;
  @Autowired PeopleUseCases people;
  @Autowired OrganisationUseCases organisation;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient db;

  OrganisationChartQuery q(String parent, String search) {
    return new OrganisationChartQuery(parent, search, 0, 12);
  }

  @Test
  void hrBrowsesRootsAndCompleteBranchWithVisibleManagerContext() {
    var roots = chart.browse("amelia", q("", ""));
    assertEquals("ROOTS", roots.mode());
    assertEquals(List.of("E001"), roots.items().stream().map(OrganisationNode::id).toList());
    assertNull(roots.parent());
    var reports = chart.browse("amelia", q("E002", ""));
    assertEquals("REPORTS", reports.mode());
    assertEquals("E002", reports.parent().id());
    assertEquals("E001", reports.manager().id());
    assertFalse(reports.managerOutsideScope());
    assertEquals(6, reports.total());
    assertEquals(6, reports.parent().visibleDirectReports());
    assertTrue(reports.items().stream().anyMatch(n -> n.id().equals("E003")));
  }

  @Test
  void managerGetsSelfAndDirectReportsWithoutHiddenManagerOrGrandchildren() {
    jdbc.update(
        "insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,manager_id,start_date) values('atlas','CHILD','Synthetic grandchild','grandchild@example.test','0','Synthetic','Design','Synthetic','Active','E003',current_date)");
    var roots = chart.browse("marcus", q("", ""));
    assertEquals(List.of("E002"), roots.items().stream().map(OrganisationNode::id).toList());
    var self = chart.browse("marcus", q("E002", ""));
    assertNull(self.manager());
    assertTrue(self.managerOutsideScope());
    assertEquals(6, self.total());
    var employee = chart.browse("marcus", q("E003", ""));
    assertEquals("E002", employee.manager().id());
    assertEquals(0, employee.parent().visibleDirectReports());
    assertEquals(0, employee.total());
    assertThrows(AccessDenied.class, () -> chart.browse("marcus", q("E001", "")));
    assertThrows(AccessDenied.class, () -> chart.browse("marcus", q("CHILD", "")));
    assertEquals(0, chart.browse("marcus", q("", "grandchild")).total());
  }

  @Test
  void employeeAndOtherTenantCannotBrowseAlteredRecords() {
    var own = chart.browse("sophia", q("", ""));
    assertEquals(List.of("E003"), own.items().stream().map(OrganisationNode::id).toList());
    assertEquals(0, own.items().getFirst().visibleDirectReports());
    assertEquals(0, chart.browse("sophia", q("", "E002")).total());
    var branch = chart.browse("sophia", q("E003", ""));
    assertNull(branch.manager());
    assertTrue(branch.managerOutsideScope());
    for (var subject : List.of("sophia", "outsider"))
      assertThrows(AccessDenied.class, () -> chart.browse(subject, q("E001", "")));
    assertThrows(AccessDenied.class, () -> chart.browse("outsider", q("E003", "")));
    assertEquals(
        List.of("E900"),
        chart.browse("outsider", q("", "")).items().stream().map(OrganisationNode::id).toList());
  }

  @Test
  void globalSearchAndPaginationReachBeyond1000WithoutDirectoryDependency() {
    jdbc.update(
        "insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,manager_id,start_date) select 'atlas','C-'||lpad(n::text,6,'0'),'Chart synthetic person','chart'||n||'@example.test','0','Synthetic','Engineering','Synthetic','Active','E002',current_date from generate_series(1,1055)n");
    var last = chart.browse("amelia", new OrganisationChartQuery("", "Chart synthetic", 21, 50));
    assertEquals(1055, last.total());
    assertEquals(5, last.items().size());
    assertEquals("C-001055", last.items().getLast().id());
    assertEquals(1055, chart.browse("marcus", q("", "Chart synthetic")).total());
    assertTrue(chart.browse("marcus", q("E002", "")).parent().visibleDirectReports() > 1000);
    assertEquals(0, chart.browse("sophia", q("", "Chart synthetic")).total());
    assertTrue(
        chart
            .browse("amelia", new OrganisationChartQuery("", "Chart synthetic", 22, 50))
            .items()
            .isEmpty());
  }

  @Test
  void approvedEffectiveFieldsAndReportingUseOneInjectedDate() {
    var date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    var hr = people.actor("amelia");
    organisation.createPosition(
        hr,
        new CreatePosition(
            "CHART-POS",
            "Chart position",
            organisation.departments(hr).stream()
                .filter(d -> d.name().equals("Engineering"))
                .findFirst()
                .orElseThrow()
                .code(),
            date,
            "Synthetic position",
            UUID.randomUUID().toString()),
        "position");
    for (var pair :
        List.of(
            new String[] {"name", "Future chart person"},
            new String[] {"title", "Future chart title"},
            new String[] {"department", "Engineering"},
            new String[] {"positionCode", "CHART-POS"},
            new String[] {"managerId", "E005"})) {
      var request =
          people.submit(
              hr,
              new Submit(
                  "E003",
                  pair[0],
                  pair[1],
                  date,
                  "Synthetic effective chart change",
                  people.profile(hr, "E003").version(),
                  UUID.randomUUID().toString()),
              "submit");
      people.decide(
          people.actor("olivia"),
          request.id(),
          new Decide("APPROVED", "Independent review"),
          "approve");
    }
    assertEquals(0, chart.browse("amelia", q("", "Future chart person")).total());
    jdbc.update(
        "insert into identity_membership values('chart-manager','atlas','E005','Synthetic manager','MANAGER')");
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> op) {
            return op.get();
          }
        };
    var future =
        new OrganisationBrowseService(
            new JdbcPeopleStore(db),
            new JdbcOrganisationChartStore(db),
            tx,
            Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    var node = future.browse("chart-manager", q("E005", "")).items().getFirst();
    assertEquals("E003", node.id());
    assertEquals("Future chart person", node.name());
    assertEquals("Future chart title", node.title());
    assertEquals("Engineering", node.department());
    assertEquals("CHART-POS", node.positionCode());
    assertEquals(0, future.browse("marcus", q("", "E003")).total());
    assertEquals(date, future.browse("amelia", q("", "")).asOf());
  }

  @Test
  void validatesFiltersBoundsAndDenyMissingParent() {
    assertThrows(InputValidation.class, () -> q("E002", "search"));
    assertThrows(InputValidation.class, () -> q("", "bad\nsearch"));
    assertThrows(InputValidation.class, () -> new OrganisationChartQuery("", "", -1, 12));
    assertThrows(InputValidation.class, () -> new OrganisationChartQuery("", "", 0, 51));
    assertThrows(AccessDenied.class, () -> chart.browse("amelia", q("UNKNOWN", "")));
  }
}
