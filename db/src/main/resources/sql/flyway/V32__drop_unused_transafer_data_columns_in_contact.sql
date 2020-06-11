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

ALTER TABLE "Contact" DROP COLUMN transfer_billing_cancellation_id;
ALTER TABLE "Contact" DROP COLUMN transfer_billing_recurrence_id;
ALTER TABLE "Contact" DROP COLUMN transfer_autorenew_poll_message_id;
ALTER TABLE "Contact" DROP COLUMN transfer_billing_event_id;
ALTER TABLE "Contact" DROP COLUMN transfer_renew_period_unit;
ALTER TABLE "Contact" DROP COLUMN transfer_renew_period_value;
ALTER TABLE "Contact" DROP COLUMN transfer_registration_expiration_time;
