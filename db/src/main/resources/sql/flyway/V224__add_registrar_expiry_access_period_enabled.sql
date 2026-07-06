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

-- Add the XAP opt-in column to Registrar, defaulting to false for all existing registrars.
-- To ensure backward compatibility with running servers (old Java code) during
-- the transition phase of the deployment, we set DEFAULT false NOT NULL.
-- TODO(mcilwain): Drop this DEFAULT constraint in a subsequent schema release once the Java code has been fully deployed.
ALTER TABLE "Registrar" ADD COLUMN expiry_access_period_enabled boolean
    DEFAULT false NOT NULL;
