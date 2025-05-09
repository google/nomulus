-- Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "PasswordResetRequest" (
    type text NOT NULL,
    request_time timestamptz NOT NULL,
    requester text NOT NULL,
    fulfillment_time timestamptz,
    destination_email text NOT NULL,
    verification_code text NOT NULL,
    PRIMARY KEY (verification_code)
);
