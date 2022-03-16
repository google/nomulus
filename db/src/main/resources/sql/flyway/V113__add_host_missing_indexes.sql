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

CREATE INDEX IDXy98mebut8ix1v07fjxxdkqcx ON "Host" (creation_time);
CREATE INDEX IDXovmntef6l45tw2bsfl56tcugx ON "Host" (deletion_time);
CREATE INDEX IDXl49vydnq0h5j1piefwjy4i8er ON "Host" (current_sponsor_registrar_id);

