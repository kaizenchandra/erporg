alter table benefit_selection drop constraint benefit_selection_status_check;
alter table benefit_selection add constraint benefit_selection_status_check check(status in ('PENDING','APPROVED','REJECTED','WITHDRAWN'));
alter table benefit_selection_audit drop constraint benefit_selection_audit_action_check;
alter table benefit_selection_audit add constraint benefit_selection_audit_action_check check(action in ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN'));

create or replace function benefits_selection_transition() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Benefit history cannot be deleted'; end if;
 if OLD.status<>'PENDING' or NEW.status not in ('APPROVED','REJECTED','WITHDRAWN') or NEW.version<>OLD.version+1
 or NEW.decided_by is null or NEW.decision_key is null or NEW.decision_reason is null or length(trim(NEW.decision_reason))=0
 or (NEW.status='WITHDRAWN' and NEW.decided_by<>OLD.requested_by)
 or (NEW.status in ('APPROVED','REJECTED') and NEW.decided_by=OLD.requested_by)
 or (to_jsonb(NEW)-array['status','version','decided_by','decision_key','decision_reason'])<>(to_jsonb(OLD)-array['status','version','decided_by','decision_key','decision_reason'])
 then raise exception 'Invalid benefit selection transition'; end if;
 return NEW;
end $$;

-- V21's deferred audit/deduction reconciliation already requires exactly one matching
-- terminal audit and no deduction for any non-APPROVED state. Retain it unchanged.
