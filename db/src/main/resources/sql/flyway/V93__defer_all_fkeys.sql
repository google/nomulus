-- Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

ALTER TABLE "Contact" DROP CONSTRAINT fk1sfyj7o7954prbn1exk7lpnoe;
ALTER TABLE "Domain" DROP CONSTRAINT fk2jc69qyg2tv9hhnmif6oa1cx1;
ALTER TABLE "RegistryLock" DROP CONSTRAINT fk2lhcwpxlnqijr96irylrh1707;
ALTER TABLE "Domain" DROP CONSTRAINT fk2u3srsfbei272093m3b3xwj23;
ALTER TABLE "SignedMarkRevocationEntry" DROP CONSTRAINT fk5ivlhvs3121yx2li5tqh54u4;
ALTER TABLE "ClaimsEntry" DROP CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq;
ALTER TABLE "GracePeriodHistory" DROP CONSTRAINT fk7w3cx8d55q8bln80e716tr7b8;
ALTER TABLE "Contact" DROP CONSTRAINT fk93c185fx7chn68uv7nl6uv2s0;
ALTER TABLE "BillingCancellation" DROP CONSTRAINT fk_billing_cancellation_billing_event_id;
ALTER TABLE "BillingCancellation" DROP CONSTRAINT fk_billing_cancellation_billing_recurrence_id;
ALTER TABLE "BillingCancellation" DROP CONSTRAINT fk_billing_cancellation_registrar_id;
ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_allocation_token;
ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_cancellation_matching_billing_recurrence_id;
ALTER TABLE "BillingEvent" DROP CONSTRAINT fk_billing_event_registrar_id;
ALTER TABLE "BillingRecurrence" DROP CONSTRAINT fk_billing_recurrence_registrar_id;
ALTER TABLE "ContactHistory" DROP CONSTRAINT fk_contact_history_contact_repo_id;
ALTER TABLE "ContactHistory" DROP CONSTRAINT fk_contact_history_registrar_id;
ALTER TABLE "Contact" DROP CONSTRAINT fk_contact_transfer_gaining_registrar_id;
ALTER TABLE "Contact" DROP CONSTRAINT fk_contact_transfer_losing_registrar_id;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_admin_contact;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_billing_contact;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_billing_recurrence_id;
ALTER TABLE "DomainHistory" DROP CONSTRAINT fk_domain_history_registrar_id;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_registrant_contact;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_tech_contact;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_tld;
ALTER TABLE "DomainTransactionRecord" DROP CONSTRAINT fk_domain_transaction_record_tld;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_transfer_billing_cancellation_id;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_transfer_billing_recurrence_id;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_transfer_gaining_registrar_id;
ALTER TABLE "Domain" DROP CONSTRAINT fk_domain_transfer_losing_registrar_id;
ALTER TABLE "DomainHost" DROP CONSTRAINT fk_domainhost_host_valid;
ALTER TABLE "GracePeriod" DROP CONSTRAINT fk_grace_period_billing_event_id;
ALTER TABLE "GracePeriod" DROP CONSTRAINT fk_grace_period_billing_recurrence_id;
ALTER TABLE "GracePeriod" DROP CONSTRAINT fk_grace_period_registrar_id;
ALTER TABLE "Host" DROP CONSTRAINT fk_host_creation_registrar_id;
ALTER TABLE "Host" DROP CONSTRAINT fk_host_current_sponsor_registrar_id;
ALTER TABLE "Host" DROP CONSTRAINT fk_host_last_epp_update_registrar_id;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_contact_repo_id;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_host_history;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_host_repo_id;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_registrar_id;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_transfer_response_gaining_registrar_id;
ALTER TABLE "PollMessage" DROP CONSTRAINT fk_poll_message_transfer_response_losing_registrar_id;
ALTER TABLE "RegistrarPoc" DROP CONSTRAINT fk_registrar_poc_registrar_id;
ALTER TABLE "DomainHistoryHost" DROP CONSTRAINT fka9woh3hu8gx5x0vly6bai327n;
ALTER TABLE "DomainTransactionRecord" DROP CONSTRAINT fkcjqe54u72kha71vkibvxhjye7;
ALTER TABLE "DomainHost" DROP CONSTRAINT fkfmi7bdink53swivs390m2btxg;
ALTER TABLE "ReservedEntry" DROP CONSTRAINT fkgq03rk0bt1hb915dnyvd3vnfc;
ALTER TABLE "Domain" DROP CONSTRAINT fkjc0r9r5y1lfbt4gpbqw4wsuvq;
ALTER TABLE "Contact" DROP CONSTRAINT fkmb7tdiv85863134w1wogtxrb2;
ALTER TABLE "PremiumEntry" DROP CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5;
ALTER TABLE "DomainDsDataHistory" DROP CONSTRAINT fko4ilgyyfnvppbpuivus565i0j;
ALTER TABLE "DelegationSignerData" DROP CONSTRAINT fktr24j9v14ph2mfuw2gsmt12kq;

ALTER TABLE "Contact" ADD CONSTRAINT fk1sfyj7o7954prbn1exk7lpnoe FOREIGN KEY (creation_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk2jc69qyg2tv9hhnmif6oa1cx1 FOREIGN KEY (creation_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "RegistryLock" ADD CONSTRAINT fk2lhcwpxlnqijr96irylrh1707 FOREIGN KEY (relock_revision_id) REFERENCES "RegistryLock"(revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk2u3srsfbei272093m3b3xwj23 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "SignedMarkRevocationEntry" ADD CONSTRAINT fk5ivlhvs3121yx2li5tqh54u4 FOREIGN KEY (revision_id) REFERENCES "SignedMarkRevocationList"(revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "ClaimsEntry" ADD CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq FOREIGN KEY (revision_id) REFERENCES "ClaimsList"(revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "GracePeriodHistory" ADD CONSTRAINT fk7w3cx8d55q8bln80e716tr7b8 FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES "DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Contact" ADD CONSTRAINT fk93c185fx7chn68uv7nl6uv2s0 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingCancellation" ADD CONSTRAINT fk_billing_cancellation_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES "BillingEvent"(billing_event_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingCancellation" ADD CONSTRAINT fk_billing_cancellation_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES "BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingCancellation" ADD CONSTRAINT fk_billing_cancellation_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingEvent" ADD CONSTRAINT fk_billing_event_allocation_token FOREIGN KEY (allocation_token) REFERENCES "AllocationToken"(token) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingEvent" ADD CONSTRAINT fk_billing_event_cancellation_matching_billing_recurrence_id FOREIGN KEY (cancellation_matching_billing_recurrence_id) REFERENCES "BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingEvent" ADD CONSTRAINT fk_billing_event_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "BillingRecurrence" ADD CONSTRAINT fk_billing_recurrence_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "ContactHistory" ADD CONSTRAINT fk_contact_history_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "ContactHistory" ADD CONSTRAINT fk_contact_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Contact" ADD CONSTRAINT fk_contact_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Contact" ADD CONSTRAINT fk_contact_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_admin_contact FOREIGN KEY (admin_contact) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_billing_contact FOREIGN KEY (billing_contact) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES "BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainHistory" ADD CONSTRAINT fk_domain_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_registrant_contact FOREIGN KEY (registrant_contact) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_tech_contact FOREIGN KEY (tech_contact) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_tld FOREIGN KEY (tld) REFERENCES "Tld"(tld_name) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainTransactionRecord" ADD CONSTRAINT fk_domain_transaction_record_tld FOREIGN KEY (tld) REFERENCES "Tld"(tld_name) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_transfer_billing_cancellation_id FOREIGN KEY (transfer_billing_cancellation_id) REFERENCES "BillingCancellation"(billing_cancellation_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_transfer_billing_recurrence_id FOREIGN KEY (transfer_billing_recurrence_id) REFERENCES "BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fk_domain_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainHost" ADD CONSTRAINT fk_domainhost_host_valid FOREIGN KEY (host_repo_id) REFERENCES "Host"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "GracePeriod" ADD CONSTRAINT fk_grace_period_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES "BillingEvent"(billing_event_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "GracePeriod" ADD CONSTRAINT fk_grace_period_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES "BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "GracePeriod" ADD CONSTRAINT fk_grace_period_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Host" ADD CONSTRAINT fk_host_creation_registrar_id FOREIGN KEY (creation_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Host" ADD CONSTRAINT fk_host_current_sponsor_registrar_id FOREIGN KEY (current_sponsor_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Host" ADD CONSTRAINT fk_host_last_epp_update_registrar_id FOREIGN KEY (last_epp_update_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES "Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_host_history FOREIGN KEY (host_repo_id, host_history_revision_id) REFERENCES "HostHistory"(host_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_host_repo_id FOREIGN KEY (host_repo_id) REFERENCES "Host"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_transfer_response_gaining_registrar_id FOREIGN KEY (transfer_response_gaining_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PollMessage" ADD CONSTRAINT fk_poll_message_transfer_response_losing_registrar_id FOREIGN KEY (transfer_response_losing_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "RegistrarPoc" ADD CONSTRAINT fk_registrar_poc_registrar_id FOREIGN KEY (registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainHistoryHost" ADD CONSTRAINT fka9woh3hu8gx5x0vly6bai327n FOREIGN KEY (domain_history_domain_repo_id, domain_history_history_revision_id) REFERENCES "DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainTransactionRecord" ADD CONSTRAINT fkcjqe54u72kha71vkibvxhjye7 FOREIGN KEY (domain_repo_id, history_revision_id) REFERENCES "DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainHost" ADD CONSTRAINT fkfmi7bdink53swivs390m2btxg FOREIGN KEY (domain_repo_id) REFERENCES "Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "ReservedEntry" ADD CONSTRAINT fkgq03rk0bt1hb915dnyvd3vnfc FOREIGN KEY (revision_id) REFERENCES "ReservedList"(revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Domain" ADD CONSTRAINT fkjc0r9r5y1lfbt4gpbqw4wsuvq FOREIGN KEY (last_epp_update_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "Contact" ADD CONSTRAINT fkmb7tdiv85863134w1wogtxrb2 FOREIGN KEY (last_epp_update_registrar_id) REFERENCES "Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "PremiumEntry" ADD CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5 FOREIGN KEY (revision_id) REFERENCES "PremiumList"(revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DomainDsDataHistory" ADD CONSTRAINT fko4ilgyyfnvppbpuivus565i0j FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES "DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "DelegationSignerData" ADD CONSTRAINT fktr24j9v14ph2mfuw2gsmt12kq FOREIGN KEY (domain_repo_id) REFERENCES "Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;
