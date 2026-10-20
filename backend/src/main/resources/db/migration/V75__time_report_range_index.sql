-- Timekeeping-owned report access path; no business snapshots or existing rows are rewritten.
-- Bounded synchronous reports filter by tenant and reporting-week range, with optional status/worker.
create index time_sheet_reporting_range on time_sheet(tenant,week desc,employee_id,id);
