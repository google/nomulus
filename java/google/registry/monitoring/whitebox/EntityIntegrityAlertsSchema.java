// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.monitoring.whitebox;

import static google.registry.bigquery.BigqueryUtils.FieldType.STRING;
import static google.registry.bigquery.BigqueryUtils.FieldType.TIMESTAMP;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.common.collect.ImmutableList;

/** The Bigquery schema for the entity integrity alerts table. */
final class EntityIntegrityAlertsSchema {

  static final String DATASET = "entity_integrity";
  static final String TABLE_ID = "alerts";
  static final String FIELD_SCANTIME = "scanTime";
  static final String FIELD_SOURCE = "source";
  static final String FIELD_TARGET = "target";
  static final String FIELD_MESSAGE = "message";

  static final ImmutableList<TableFieldSchema> SCHEMA_FIELDS =
      ImmutableList.of(
          new TableFieldSchema().setName(FIELD_SCANTIME).setType(TIMESTAMP.name()),
          new TableFieldSchema().setName(FIELD_SOURCE).setType(STRING.name()),
          new TableFieldSchema().setName(FIELD_TARGET).setType(STRING.name()),
          new TableFieldSchema().setName(FIELD_MESSAGE).setType(STRING.name()));

  private EntityIntegrityAlertsSchema() {}
}

