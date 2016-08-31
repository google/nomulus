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

import static com.google.apphosting.api.ApiProxy.getCurrentEnvironment;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.common.collect.ImmutableList;
import google.registry.bigquery.BigqueryUtils.FieldType;
import google.registry.model.eppoutput.Result.Code;
import google.registry.request.RequestScope;
import javax.inject.Inject;

/** The EPP Metrics collector. See {@link Metrics}. */
@RequestScope
public class EppMetrics extends Metrics {

  private static final String REQUEST_LOG_ID = "com.google.appengine.runtime.request_log_id";

  static final String TABLE_ID = "eppMetrics";

  static final ImmutableList<TableFieldSchema> SCHEMA_FIELDS =
      ImmutableList.of(
          new TableFieldSchema().setName("requestId").setType(FieldType.STRING.name()),
          new TableFieldSchema().setName("startTime").setType(FieldType.TIMESTAMP.name()),
          new TableFieldSchema().setName("endTime").setType(FieldType.TIMESTAMP.name()),
          new TableFieldSchema().setName("commandName").setType(FieldType.STRING.name()),
          new TableFieldSchema().setName("clientId").setType(FieldType.STRING.name()),
          new TableFieldSchema().setName("privilegeLevel").setType(FieldType.STRING.name()),
          new TableFieldSchema().setName("eppTarget").setType(FieldType.STRING.name()),
          new TableFieldSchema().setName("eppStatus").setType(FieldType.INTEGER.name()),
          new TableFieldSchema().setName("attempts").setType(FieldType.INTEGER.name()));

  @Inject
  public EppMetrics() {
    setTableId(TABLE_ID);
    fields.put("attempts", 0);
    fields.put("requestId", getCurrentEnvironment().getAttributes().get(REQUEST_LOG_ID).toString());
  }

  public void setCommandName(String name) {
    fields.put("commandName", name);
  }

  public void setClientId(String clientId) {
    fields.put("clientId", clientId);
  }

  public void setPrivilegeLevel(String level) {
    fields.put("privilegeLevel", level);
  }

  public void setEppTarget(String eppTarget) {
    fields.put("eppTarget", eppTarget);
  }

  public void setEppStatus(Code status) {
    fields.put("eppStatus", String.valueOf(status.code));
  }

  public void incrementAttempts() {
    fields.put("attempts", ((int) fields.get("attempts")) + 1);
  }
}
