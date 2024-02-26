-- Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "ConsoleEppActionHistory" (
  history_revision_id int8 NOT NULL,
  history_date_time timestamptz NOT NULL,
  history_method text NOT NULL,
  history_request_body text,
  history_type text NOT NULL,
  history_url text NOT NULL,
  history_entry_class text NOT NULL,
  repo_id text NOT NULL,
  revision_id int8 NOT NULL,
  history_acting_user text NOT NULL,
  PRIMARY KEY (history_revision_id),
  CONSTRAINT FKb686b9os2nsjpv930npa4r3b4
    FOREIGN KEY(history_acting_user)
    REFERENCES "User" (email_address)
);

CREATE TABLE "RegistrarPocUpdateHistory" (
  history_revision_id int8 NOT NULL,
  history_date_time timestamptz NOT NULL,
  history_method text NOT NULL,
  history_request_body text,
  history_type text NOT NULL,
  history_url text NOT NULL,
  email_address text NOT NULL,
  registrar_id text NOT NULL,
  allowed_to_set_registry_lock_password boolean NOT NULL,
  fax_number text,
  login_email_address text,
  name text,
  phone_number text,
  registry_lock_email_address text,
  registry_lock_password_hash text,
  registry_lock_password_salt text,
  types text[],
  visible_in_domain_whois_as_abuse boolean NOT NULL,
  visible_in_whois_as_admin boolean NOT NULL,
  visible_in_whois_as_tech boolean NOT NULL,
  history_acting_user text NOT NULL,
  PRIMARY KEY (history_revision_id),
  CONSTRAINT FKftpbwctxtkc1i0njc0tdcaa2g
    FOREIGN KEY(history_acting_user)
    REFERENCES "User" (email_address),
  CONSTRAINT FKRegistrarPocUpdateHistoryEmailAddress
    FOREIGN KEY(email_address, registrar_id)
    REFERENCES "RegistrarPoc" (email_address, registrar_id)
);

CREATE TABLE "RegistrarUpdateHistory" (
  history_revision_id int8 NOT NULL,
  history_date_time timestamptz NOT NULL,
  history_method text NOT NULL,
  history_request_body text,
  history_type text NOT NULL,
  history_url text NOT NULL,
  allowed_tlds text[],
  billing_account_map hstore,
  block_premium_names boolean NOT NULL,
  client_certificate text,
  client_certificate_hash text,
  contacts_require_syncing boolean NOT NULL,
  creation_time timestamptz NOT NULL,
  drive_folder_id text,
  email_address text,
  failover_client_certificate text,
  failover_client_certificate_hash text,
  fax_number text,
  iana_identifier int8,
  icann_referral_email text,
  i18n_address_city text,
  i18n_address_country_code text,
  i18n_address_state text,
  i18n_address_street_line1 text,
  i18n_address_street_line2 text,
  i18n_address_street_line3 text,
  i18n_address_zip text,
  ip_address_allow_list text[],
  last_certificate_update_time timestamptz,
  last_expiring_cert_notification_sent_date timestamptz,
  last_expiring_failover_cert_notification_sent_date timestamptz,
  localized_address_city text,
  localized_address_country_code text,
  localized_address_state text,
  localized_address_street_line1 text,
  localized_address_street_line2 text,
  localized_address_street_line3 text,
  localized_address_zip text,
  password_hash text,
  phone_number text,
  phone_passcode text,
  po_number text,
  rdap_base_urls text[],
  registrar_name text NOT NULL,
  registry_lock_allowed boolean NOT NULL,
  password_salt text,
  state text,
  type text NOT NULL,
  url text,
  whois_server text,
  update_timestamp timestamptz,
  registrar_id text NOT NULL,
  history_acting_user text NOT NULL,
  PRIMARY KEY (history_revision_id),
  CONSTRAINT FKsr7w342s7x5s5jvdti2axqeq8
    FOREIGN KEY (history_acting_user)
    REFERENCES "User" (email_address),
  CONSTRAINT FKRegistrarUpdateHistoryRegistrarId
    FOREIGN KEY (registrar_id)
    REFERENCES "Registrar" (registrar_id)
);

CREATE TABLE "UserUpdateHistory" (
  history_revision_id int8 NOT NULL,
  history_date_time timestamptz NOT NULL,
  history_method text NOT NULL,
  history_request_body text,
  history_type text NOT NULL,
  history_url text NOT NULL,
  user_id int8 NOT NULL,
  email_address text NOT NULL,
  registry_lock_password_hash text,
  registry_lock_password_salt text,
  global_role text NOT NULL,
  is_admin boolean NOT NULL,
  registrar_roles hstore,
  update_timestamp timestamptz,
  history_acting_user text NOT NULL,
  PRIMARY KEY (history_revision_id),
  CONSTRAINT FK1s7bopbl3pwrhv3jkkofnv3o0
    FOREIGN KEY (history_acting_user)
    REFERENCES "User" (email_address),
  CONSTRAINT FKUserUpdateHistoryEmailAddress
    FOREIGN KEY (email_address)
    REFERENCES "User" (email_address)
);
