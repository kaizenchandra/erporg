-- Only original manual entries have a complete reversal workflow in this increment.
-- Reversing a reversal would otherwise leave the earlier original's status inconsistent.
create function fin_reversal_source_guard() returns trigger language plpgsql as $$
begin
 if new.source_type='REVERSAL' and not exists(select 1 from fin_journal j where j.tenant=new.tenant and j.entity=new.entity and j.id=new.source_id and j.source_type='MANUAL' and j.status='POSTED') then raise exception 'Only posted original manual journals may create reversal drafts';end if;
 return new;
end $$;
create trigger fin_reversal_source before insert on fin_journal for each row execute function fin_reversal_source_guard();
