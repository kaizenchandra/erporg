create function document_hold_release_subject_guard() returns trigger language plpgsql as $$
begin
 if NEW.action='RELEASE_HOLD' and exists(select 1 from document_retention_event p where p.tenant=NEW.tenant and p.root_id=NEW.root_id and p.action='PLACE_HOLD' and p.hold_id=NEW.hold_id and p.actor=NEW.actor) then raise exception 'Original hold subject cannot release after identity rebinding'; end if;
 return NEW;
end $$;
create trigger document_hold_release_subject_guard before insert on document_retention_event for each row execute function document_hold_release_subject_guard();
