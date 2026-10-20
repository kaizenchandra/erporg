-- Financial guards must resolve owned tables/functions independently of caller search_path.
-- Preserve the owning schema as current_schema() so JDBC and trigger advisory locks agree.
do $$
declare owned_schema text:=current_schema(); routine text;
begin
 foreach routine in array array['fin_period_guard','fin_journal_guard','fin_reversal_guard','pay_run_guard','pay_result_provenance_guard','fin_document_provenance_guard','fin_reversal_source_guard'] loop
  execute format('alter function %I.%I() set search_path to %I, pg_catalog',owned_schema,routine,owned_schema);
 end loop;
 execute format('alter function %I.pay_round_even(numeric) set search_path to %I, pg_catalog',owned_schema,owned_schema);
end $$;
