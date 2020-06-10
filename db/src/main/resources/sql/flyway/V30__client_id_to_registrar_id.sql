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

ALTER TABLE IF EXISTS "Domain" RENAME creation_client_id TO creation_registrar_id;
ALTER TABLE IF EXISTS "Domain" RENAME current_sponsor_client_id TO current_sponsor_registrar_id;
ALTER TABLE IF EXISTS "Domain" RENAME last_epp_update_client_id TO last_epp_update_registrar_id;

ALTER TABLE IF EXISTS "Contact" RENAME creation_client_id TO creation_registrar_id;
ALTER TABLE IF EXISTS "Contact" RENAME current_sponsor_client_id TO current_sponsor_registrar_id;
ALTER TABLE IF EXISTS "Contact" RENAME last_epp_update_client_id TO last_epp_update_registrar_id;

ALTER TABLE IF EXISTS "HostResource" RENAME creation_client_id TO creation_registrar_id;
ALTER TABLE IF EXISTS "HostResource" RENAME current_sponsor_client_id TO current_sponsor_registrar_id;
ALTER TABLE IF EXISTS "HostResource" RENAME last_epp_update_client_id TO last_epp_update_registrar_id;

ALTER TABLE IF EXISTS "Registrar" RENAME client_id TO registrar_id;

ALTER TABLE IF EXISTS "BillingCancellation" RENAME client_id TO registrar_id;
ALTER TABLE IF EXISTS "BillingCancellation" RENAME CONSTRAINT fk_billing_cancellation_client_id TO fk_billing_cancellation_registrar_id;
ALTER TABLE IF EXISTS "BillingEvent" RENAME client_id TO registrar_id;
ALTER TABLE IF EXISTS "BillingEvent" RENAME CONSTRAINT fk_billing_event_client_id TO fk_billing_event_registrar_id;
ALTER TABLE IF EXISTS "BillingRecurrence" RENAME client_id TO registrar_id;
ALTER TABLE IF EXISTS "BillingRecurrence" RENAME CONSTRAINT fk_billing_recurrence_client_id TO fk_billing_recurrence_registrar_id;
