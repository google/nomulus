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

ALTER TABLE "Contact" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
ALTER TABLE "ContactHistory" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
ALTER TABLE "Domain" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
ALTER TABLE "DomainHistory" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
ALTER TABLE "Host" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
ALTER TABLE "HostHistory" ADD COLUMN IF NOT EXISTS "transfer_poll_message_id_3" bigint;
