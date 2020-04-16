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

alter table if exists "Contact"
   add constraint FK1sfyj7o7954prbn1exk7lpnoe
   foreign key (creation_client_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint FK93c185fx7chn68uv7nl6uv2s0
   foreign key (current_sponsor_client_id)
   references "Registrar";

alter table if exists "Contact"
   add constraint FKmb7tdiv85863134w1wogtxrb2
   foreign key (last_epp_update_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FK2jc69qyg2tv9hhnmif6oa1cx1
   foreign key (creation_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FK2u3srsfbei272093m3b3xwj23
   foreign key (current_sponsor_client_id)
   references "Registrar";

alter table if exists "Domain"
   add constraint FKjc0r9r5y1lfbt4gpbqw4wsuvq
   foreign key (last_epp_update_client_id)
   references "Registrar";
