package com.atlas.modules.timekeeping.application.port.out;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.model.TimeModels.Period;
import java.time.*;
import java.util.*;

public interface TimeStore {
  Optional<String> batchFingerprint(String tenant, String subject, String key);

  void rememberBatch(String tenant, String subject, String key, String fingerprint);

  Optional<Period> period(String tenant, java.time.LocalDate week);

  List<Period> periodHistory(String tenant, java.time.LocalDate week);

  Optional<Period> periodReceipt(String tenant, String subject, String commandKey);

  void savePeriod(String tenant, Period period);

  java.util.List<ActorAction> actors(String tenant, String entityId);

  void lock(String tenant, String employeeId);

  Optional<Receipt> receipt(String tenant, String subject, String key);

  void remember(String tenant, String subject, String key, String fingerprint, Outcome result);

  Optional<Shift> shift(String tenant, String id);

  Optional<Shift> openShift(String tenant, String employeeId, LocalDate episodeStart);

  boolean overlaps(
      String tenant,
      String employeeId,
      LocalDate episodeStart,
      String except,
      Instant start,
      Instant end);

  void saveShift(String tenant, Shift shift);

  Page<Shift> shifts(String tenant, String employeeId, int page, int size);

  Optional<Sheet> sheet(String tenant, String id);

  Optional<Sheet> week(String tenant, String employeeId, LocalDate week, LocalDate episodeStart);

  void saveSheet(String tenant, Sheet sheet);

  Page<Sheet> sheets(
      String tenant,
      List<String> employeeIds,
      boolean all,
      String excludeEmployeeId,
      String status,
      int page,
      int size);

  List<ReportRow> report(String tenant, List<String> employeeIds, boolean all, ReportQuery query);

  void allocate(String tenant, Sheet sheet, Instant now);

  List<Allocation> allocations(String tenant, String sheetId);

  void audit(
      String tenant,
      String employeeId,
      String entityId,
      String action,
      String actor,
      String reason,
      Object before,
      Object after,
      Instant now,
      String correlation);

  Page<Audit> history(String tenant, String employeeId, int page, int size);
}
