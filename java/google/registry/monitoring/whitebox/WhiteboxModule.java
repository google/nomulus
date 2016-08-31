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

import static google.registry.request.RequestParameters.extractRequiredParameter;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import google.registry.request.Parameter;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;

/**
 * Dagger module for injecting common settings for Whitebox tasks.
 */
@Module
public class WhiteboxModule {

  @Provides
  @IntoMap
  @StringKey(EppMetrics.TABLE_ID)
  static ImmutableList<TableFieldSchema> provideEppMetricsSchema() {
    return EppMetrics.SCHEMA_FIELDS;
  }

  @Provides
  @IntoMap
  @StringKey(EntityIntegrityAlertsSchema.TABLE_ID)
  static ImmutableList<TableFieldSchema> provideEntityIntegrityAlertsSchema() {
    return EntityIntegrityAlertsSchema.SCHEMA_FIELDS;
  }

  @Provides
  @Parameter("tableId")
  static String provideTableId(HttpServletRequest req) {
    return extractRequiredParameter(req, "tableId");
  }

  @Provides
  @Parameter("insertId")
  static String provideInsertId(HttpServletRequest req) {
    return extractRequiredParameter(req, "insertId");
  }

  @Provides
  static Supplier<String> provideIdGenerator() {
    return new Supplier<String>() {
      @Override
      public String get() {
        return UUID.randomUUID().toString();
      }
    };
  }
}
