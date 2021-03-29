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

ALTER TABLE "Registrar" ALTER COLUMN "last_update_time" SET NOT NULL;
ALTER TABLE "RegistryLock" RENAME "last_update_timestamp" TO "last_update_time";
ALTER TABLE "RegistryLock" ALTER COLUMN "last_update_time" SET NOT NULL;

-- While we're at it, rename some registry-lock fields to follow the same naming pattern
ALTER TABLE "RegistryLock" RENAME "lock_completion_timestamp" TO "lock_completion_time";
ALTER TABLE "RegistryLock" RENAME "lock_request_timestamp" TO "lock_request_time";
ALTER TABLE "RegistryLock" RENAME "unlock_completion_timestamp" TO "unlock_completion_time";
ALTER TABLE "RegistryLock" RENAME "unlock_request_timestamp" TO "unlock_request_time";
