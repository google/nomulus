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

ALTER TABLE "Domain" ADD COLUMN admin_contact text;
ALTER TABLE "Domain" ADD COLUMN billing_contact text;
ALTER TABLE "Domain" ADD COLUMN registrant_contact text;
ALTER TABLE "Domain" ADD COLUMN tech_contact text;

alter table if exists "Domain"
   add constraint fk_domain_admin_contact
   foreign key (admin_contact)
   references "Contact";

alter table if exists "Domain"
   add constraint fk_domain_billing_contact
   foreign key (billing_contact)
   references "Contact";

alter table if exists "Domain"
   add constraint fk_domain_registrant_contact
   foreign key (registrant_contact)
   references "Contact";

alter table if exists "Domain"
   add constraint fk_domain_tech_contact
   foreign key (tech_contact)
   references "Contact";
