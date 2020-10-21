-- Copyright 2020 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

alter table if exists "RegistrarPoc"
   add constraint fk_registrar_poc_registrar_id
   foreign key (registrar_id)
   references "Registrar";

alter table if exists "Spec11ThreatMatch"
   add constraint fk_spec11_threat_match_domain_repo_id
   foreign key (domain_repo_id)
   references "Domain";

alter table if exists "Spec11ThreatMatch"
   add constraint fk_spec11_threat_match_registrar_id
   foreign key (registrar_id)
   references "Registrar";

alter table if exists "Spec11ThreatMatch"
   add constraint fk_spec11_threat_match_tld
   foreign key (tld)
   references "Tld";

alter table if exists "BillingEvent"
   add constraint fk_billing_event_domain_history
   foreign key (domain_repo_id, domain_history_revision_id)
   references "DomainHistory";

alter table if exists "BillingEvent"
   add constraint fk_billing_event_domain_repo_id
   foreign key (domain_repo_id)
   references "Domain";

alter table "BillingEvent" rename allocation_token_id to allocation_token;

alter table if exists "BillingEvent"
   add constraint fk_billing_event_allocation_token
   foreign key (allocation_token)
   references "AllocationToken";

alter table if exists "BillingRecurrence"
   add constraint fk_billing_recurrence_domain_history
   foreign key (domain_repo_id, domain_history_revision_id)
   references "DomainHistory";

alter table if exists "BillingRecurrence"
   add constraint fk_billing_recurrence_domain_repo_id
   foreign key (domain_repo_id)
   references "Domain";

alter table if exists "BillingCancellation"
   add constraint fk_billing_cancellation_domain_history
   foreign key (domain_repo_id, domain_history_revision_id)
   references "DomainHistory";

alter table if exists "BillingCancellation"
   add constraint fk_billing_cancellation_domain_repo_id
   foreign key (domain_repo_id)
   references "Domain";

alter table if exists "Contact"
   add constraint fk_contact_transfer_gaining_poll_message_id
   foreign key (transfer_gaining_poll_message_id)
   references "PollMessage";

alter table if exists "Contact"
   add constraint fk_contact_transfer_losing_poll_message_id
   foreign key (transfer_losing_poll_message_id)
   references "PollMessage";

alter table if exists "Domain"
   add constraint fk_domain_tld
   foreign key (tld)
   references "Tld";

alter table if exists "DomainTransactionRecord"
   add constraint fk_domain_transaction_record_tld
   foreign key (tld)
   references "Tld";

alter table if exists "GracePeriod"
   add constraint fk_grace_period_registrar_id
   foreign key (registrar_id)
   references "Registrar";

alter table if exists "Host"
   add constraint fk_host_creation_registrar_id
   foreign key (creation_registrar_id)
   references "Registrar";

alter table if exists "Host"
   add constraint fk_host_current_sponsor_registrar_id
   foreign key (current_sponsor_registrar_id)
   references "Registrar";

alter table if exists "Host"
   add constraint fk_host_last_epp_update_registrar_id
   foreign key (last_epp_update_registrar_id)
   references "Registrar";
