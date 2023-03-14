-- Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

ALTER TABLE "DnsRefreshRequest" RENAME COLUMN process_time TO last_process_time;
CREATE INDEX IDXfdk2xpil2x1gh0omt84k2y3o1 ON "DnsRefreshRequest" (last_process_time);
DROP INDEX IDX3i7i2ktts9d7lcjbs34h0pvwo;



