package com.atlas.modules.timekeeping.adapter.in.web;

import com.atlas.modules.timekeeping.application.model.TimeModels.Report;

/** CSV transport rendering: quoted cells and spreadsheet formula neutralisation. */
public final class TimeReportCsv {
  private TimeReportCsv() {}

  public static String cell(String value) {
    var first = value.stripLeading();
    if (!first.isEmpty() && "=+-@".indexOf(first.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  public static String encode(Report report) {
    var csv =
        new StringBuilder(
            "Sheet ID,Worker,Reporting week,Status,Revision,Working minutes,Allocated minutes\r\n");
    for (var row : report.items()) {
      csv.append(cell(row.id()))
          .append(',')
          .append(cell(row.employeeId()))
          .append(',')
          .append(cell(row.week().toString()))
          .append(',')
          .append(cell(row.status()))
          .append(',')
          .append(row.version())
          .append(',')
          .append(row.totalMinutes())
          .append(',')
          .append(row.allocatedMinutes())
          .append("\r\n");
    }
    return csv.toString();
  }
}
