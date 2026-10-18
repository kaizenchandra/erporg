-- Synthetic historical fixture only. Executed explicitly by the final browser suite,
-- never packaged as an application migration or exposed as an API/time-control endpoint.
begin;
set local search_path=atlas_acceptance;
insert into hr_employee(tenant,id,name,email,phone,title,department,location,status,manager_id,start_date,version)
values('atlas','E-REHIRE-TEST','Alex Morgan','alex.rehire@example.test','+1 202 555 1800','Former analyst','Engineering','Remote','Active','E002','2023-01-01',1);
insert into identity_membership(subject,tenant,employee_id,name,role) values('rehire-former-test','atlas','E-REHIRE-TEST','Alex Morgan','HR');
insert into hr_resignation(tenant,id,employee_id,last_working_date,reason,status,requested_by,employee_version,recorded_at,version,idempotency_key,decided_by,reviewer_employee_id,comment,decided_at,decision_key,decision_input_version)
values('atlas','rehire-former-resignation','E-REHIRE-TEST',(now() at time zone 'UTC')::date-10,'Historical synthetic departure','APPROVED','rehire-former-test',0,now()-interval '20 days',1,'former-submit-001','amelia','E001','Historical independent approval',now()-interval '19 days','former-review-001',0);
insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id)
select tenant,id,null,'SUBMITTED',requested_by,reason,last_working_date,recorded_at,'former-submit' from hr_resignation where tenant='atlas' and id='rehire-former-resignation';
insert into hr_resignation_audit(tenant,request_id,old_status,new_status,actor,reason,effective_date,recorded_at,correlation_id)
select tenant,id,'SUBMITTED','APPROVED',decided_by,comment,last_working_date,decided_at,'former-review' from hr_resignation where tenant='atlas' and id='rehire-former-resignation';
insert into hr_case_episode(tenant,request_id,employee_id,episode_number,episode_start_date,source_recorded_at,linked_at,origin,linked_by,correlation_id)
select r.tenant,r.id,r.employee_id,1,e.start_date,r.recorded_at,r.recorded_at,'COMMAND',r.requested_by,'former-submit' from hr_resignation r join hr_employee e on e.tenant=r.tenant and e.id=r.employee_id where r.tenant='atlas' and r.id='rehire-former-resignation';
insert into hr_resignation_handoff(tenant,request_id,employee_id,effective_date,recorded_at,correlation_id,status)
select tenant,id,employee_id,last_working_date,decided_at,'former-review','PENDING_EXIT_COORDINATION' from hr_resignation where tenant='atlas' and id='rehire-former-resignation';
insert into hr_employment_end(tenant,request_id,employee_id,last_working_date,inactive_date,actor,actor_employee_id,reason,input_version,new_version,old_status,new_status,recorded_at,correlation_id,idempotency_key)
select tenant,id,employee_id,last_working_date,last_working_date+1,'amelia','E001','Historical synthetic end',0,1,'Active','Ended',now()-interval '18 days','former-end','former-end-001' from hr_resignation where tenant='atlas' and id='rehire-former-resignation';
insert into hr_employment_end_handoff(tenant,employee_id,target,inactive_date,recorded_at,correlation_id,status)
select e.tenant,e.employee_id,t.target,e.inactive_date,e.recorded_at,e.correlation_id,'PENDING' from hr_employment_end e cross join (values('FINAL_PAY'),('EXTERNAL_IDENTITY')) t(target) where e.tenant='atlas' and e.employee_id='E-REHIRE-TEST';
commit;
