-- Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

 create table "PackagePromotion" (
       package_promotion_id int8 not null,
        last_notification_sent timestamptz,
        max_creates int4 not null,
        max_domains int4 not null,
        next_billing_date timestamptz not null,
        package_price_amount numeric(19, 2),
        package_price_currency text,
        token text not null,
        primary key (package_promotion_id)
    );

create index IDXlg6a5tp70nch9cp0gc11brc5o on "PackagePromotion" (token);

