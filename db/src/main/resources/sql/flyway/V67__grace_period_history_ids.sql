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

ALTER TABLE "GracePeriod" ADD COLUMN billing_event_history_id int8;
ALTER TABLE "GracePeriod" ADD COLUMN billing_recurrence_history_id int8;

ALTER TABLE ONLY public."GracePeriod"
    DROP CONSTRAINT fk2mys4hojm6ev2g9tmy5aq6m7g;
ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_domain_repo_id
    FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "GracePeriod" ALTER COLUMN id drop default;
DROP SEQUENCE "GracePeriod_id_seq";
