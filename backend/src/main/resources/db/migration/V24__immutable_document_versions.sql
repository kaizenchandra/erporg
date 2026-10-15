create table employee_document_version (
 tenant varchar(80) not null,root_id varchar(36) not null,parent_id varchar(36) not null,document_id varchar(36) not null,version integer not null check(version between 2 and 100),actor varchar(120) not null,reason varchar(500) not null,recorded_at timestamptz not null,correlation_id varchar(80) not null,
 primary key(tenant,document_id),unique(tenant,root_id,version),unique(tenant,parent_id),foreign key(tenant,root_id) references employee_document(tenant,id),foreign key(tenant,parent_id) references employee_document(tenant,id),foreign key(tenant,document_id) references employee_document(tenant,id),check(document_id<>root_id and document_id<>parent_id)
);
create trigger immutable_document_version before update or delete on employee_document_version for each row execute function documents_immutable_record();
create function document_version_integrity() returns trigger language plpgsql as $$
declare root employee_document%rowtype; child employee_document%rowtype; latest_id varchar(36); latest_version integer;
begin
 select * into root from employee_document where tenant=NEW.tenant and id=NEW.root_id for update;
 select * into child from employee_document where tenant=NEW.tenant and id=NEW.document_id;
 if root.id is null or child.id is null or root.employee_id<>child.employee_id or child.requested_by<>NEW.actor or child.reason<>NEW.reason or child.recorded_at<>NEW.recorded_at or exists(select 1 from employee_document_version where tenant=NEW.tenant and document_id=NEW.root_id) then raise exception 'Invalid document version owner/snapshot'; end if;
 select document_id,version into latest_id,latest_version from employee_document_version where tenant=NEW.tenant and root_id=NEW.root_id order by version desc limit 1;
 if NEW.parent_id<>coalesce(latest_id,NEW.root_id) or NEW.version<>coalesce(latest_version,1)+1 then raise exception 'Document versions must be sequential'; end if;
 return NEW;
end $$;
create trigger document_version_integrity before insert on employee_document_version for each row execute function document_version_integrity();
