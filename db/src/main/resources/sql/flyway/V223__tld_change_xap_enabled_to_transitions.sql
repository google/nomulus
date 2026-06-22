-- Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

-- Drop the old unused boolean column. Since it was never mapped in Tld.java
-- on master, no running servers map or use it, making it completely safe to drop.
ALTER TABLE "Tld" DROP COLUMN expiry_access_period_enabled;

-- Add the new transitions column as NOT NULL with a temporary DEFAULT value to
-- ensure backward compatibility with the running servers (old Java code) during
-- the transition phase of the deployment.
-- TODO(mcilwain): Drop this DEFAULT constraint in a subsequent schema release
-- once the Java code mapping this column has been fully deployed.
ALTER TABLE "Tld" ADD COLUMN expiry_access_period_transitions hstore
    DEFAULT '"1970-01-01T00:00:00.000Z"=>"DISABLED"'::hstore NOT NULL;
