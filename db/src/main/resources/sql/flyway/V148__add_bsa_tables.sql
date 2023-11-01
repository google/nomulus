-- Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

CREATE TABLE "BsaDomainInUse" (
    label text not null,
    tld text not null,
    creation_time timestamptz not null,
    reason text not null,
    primary key (label, tld)
);

CREATE TABLE "BsaDownload" (
    job_id  bigserial not null,
    block_list_checksums text not null,
    creation_time timestamptz not null,
    stage text not null,
    update_timestamp timestamptz,
    primary key (job_id)
);

CREATE TABLE "BsaLabel" (
    label text not null,
    creation_time timestamptz not null,
    primary key (label)
);

CREATE INDEX IDXthwx7norw3h81t13ab6w8hje
    ON "BsaDownload" (creation_time, stage);

ALTER TABLE IF EXISTS "BsaDomainInUse"
    ADD CONSTRAINT FKbsadomaininuse2label
    FOREIGN KEY (label)
    REFERENCES "BsaLabel" (label)
    ON DELETE CASCADE;
