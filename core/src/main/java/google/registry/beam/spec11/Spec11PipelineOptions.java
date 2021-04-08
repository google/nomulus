// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.spec11;

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Description;

/** Custom options for running the spec11 pipeline. */
public interface Spec11PipelineOptions extends RegistryPipelineOptions {

  @Description("The ID of the project from which the source data is read.")
  String getProjectId();

  void setProjectId(String value);

  @Description("The local date we generate the report for, in yyyy-MM-dd format.")
  String getDate();

  void setDate(String value);

  @Description("The API key we use to access the SafeBrowsing API.")
  String getSafeBrowsingApiKey();

  void setSafeBrowsingApiKey(String value);

  @Description("The GCS bucket URL for Spec11 reports to be uploaded.")
  String getReportingBucketUrl();

  void setReportingBucketUrl(String value);
}
