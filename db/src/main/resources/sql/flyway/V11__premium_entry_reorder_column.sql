-- Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

-- Drop and re-add the price column so that it's correctly at the end of the
-- table as expected by JPA's automatic alphabetical ordering of the
-- PremiumEntry fields. Since we don't have real production data we care about
-- here just yet, it's OK to lose this data.
alter table "PremiumEntry" drop column if exists price;

-- TODO(mcilwain): Remove this default
alter table "PremiumEntry" add column price numeric(19, 2) not null default 0;

-- Remove default set in V9 that we no longer need.
alter table "PremiumList" alter column currency drop default;
