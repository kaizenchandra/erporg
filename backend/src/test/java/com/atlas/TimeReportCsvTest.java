package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.timekeeping.adapter.in.web.TimeReportCsv;
import org.junit.jupiter.api.Test;

class TimeReportCsvTest {
  @Test
  void spreadsheetFormulasWhitespaceQuotesAndNewlinesCannotEscapeCells() {
    assertEquals("\"'=SUM(1,2)\"", TimeReportCsv.cell("=SUM(1,2)"));
    assertEquals("\"'  +1\"", TimeReportCsv.cell("  +1"));
    assertEquals("\"'\t@cmd\"", TimeReportCsv.cell("\t@cmd"));
    assertEquals("\"cost,\"\"tag\"\"\nline\"", TimeReportCsv.cell("cost,\"tag\"\nline"));
    assertEquals("\"E003\"", TimeReportCsv.cell("E003"));
  }
}
