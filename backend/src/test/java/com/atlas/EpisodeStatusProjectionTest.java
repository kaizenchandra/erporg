package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
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
class EpisodeStatusProjectionTest {
  @Autowired JdbcTemplate db;

  // Deliberately isolated read-contract fixtures: these are not supported lifecycle writes.
  @Test
  void selectsEpisodeBeforeItsOwnEndAndCompletionWithoutTenantOrPredecessorLeakage()
      throws Exception {
    db.execute("create schema episode_projection_contract");
    db.execute("set local search_path to episode_projection_contract");
    db.execute(
        "create table hr_employee(tenant varchar,id varchar,start_date date,status varchar)");
    db.execute(
        "create table hr_episode_end_current(tenant varchar,employee_id varchar,episode_number integer,episode_start_date date,inactive_date date)");
    db.execute(
        "create table hr_employment_episode(tenant varchar,employee_id varchar,number integer,start_date date,status varchar,rehire_id varchar)");
    db.execute(
        "create table hr_rehire_completion(tenant varchar,employee_id varchar,rehire_id varchar,effective_date date)");
    db.execute(
        "insert into hr_employee values('a','employee','2020-01-01','On leave'),('b','employee','2020-01-01','Active')");
    db.execute(
        "insert into hr_employment_episode values('a','employee',2,'2025-02-01','Onboarding','return-2')");
    db.execute(
        "insert into hr_episode_end_current values('a','employee',1,'2020-01-01','2025-01-01'),('b','employee',1,'2020-01-01','2024-01-01'),('a','employee',2,'1999-01-01','2025-02-01')");
    db.execute(
        "insert into hr_rehire_completion values('a','employee','another-return','2025-02-01'),('b','employee','return-2','2025-02-01')");
    db.execute(
        Files.readString(
            Path.of("src/main/resources/db/migration/V45__episode_local_status_projection.sql")));
    assertEquals("On leave", status("a", "2024-12-31"));
    assertEquals("Ended", status("a", "2025-01-01"));
    assertEquals("Ended", status("a", "2025-01-31"));
    assertEquals("Onboarding", status("a", "2025-02-01"));
    assertEquals("Ended", status("b", "2025-02-01"));
    db.execute("insert into hr_rehire_completion values('a','employee','return-2','2025-02-03')");
    assertEquals("Onboarding", status("a", "2025-02-02"));
    assertEquals("Active", status("a", "2025-02-03"));
    db.execute(
        "insert into hr_episode_end_current values('a','employee',2,'2025-02-01','2025-02-05')");
    assertEquals("Active", status("a", "2025-02-04"));
    assertEquals("Ended", status("a", "2025-02-05"));
    assertEquals("Ended", status("a", "2025-02-06"));
    assertEquals(Date.valueOf("2020-01-01"), start("2025-01-31"));
    assertEquals(Date.valueOf("2025-02-01"), start("2025-02-01"));
    assertEquals(
        "fallback",
        db.queryForObject(
            "select hr_employment_status('missing','employee','fallback','2025-02-01')",
            String.class));
  }

  String status(String tenant, String date) {
    return db.queryForObject(
        "select hr_employment_status(?::varchar,'employee'::varchar,'fallback'::varchar,?::date)",
        String.class,
        tenant,
        date);
  }

  Date start(String date) {
    return db.queryForObject(
        "select hr_employment_start('a'::varchar,'employee'::varchar,'1990-01-01'::date,?::date)",
        Date.class,
        date);
  }
}
