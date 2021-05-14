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

create index IDXnb02m43jcx24r64n8rbg22u4q on "Domain" (admin_contact);

create index IDXq9gy8x2xynt9tb16yajn1gcm8 on "Domain" (billing_contact);

create index IDXr22ciyccwi9rrqmt1ro0s59qf on "Domain" (tech_contact);

create index IDXa7fu0bqynfb79rr80528b4jqt on "Domain" (registrant_contact);

