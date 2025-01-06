// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.wipeout;

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;

public interface WipeOutContactHistoryPiiPipelineOptions extends RegistryPipelineOptions {

  @Description(
      "A contact history entry with a history modification time before this time will have its PII"
          + " wiped, unless it is the most entry for the contact.")
  String getCutoffTime();

  void setCutoffTime(String value);

  @Description(
      "If true, the wiped out billing events will not be saved but the pipeline metrics counter"
          + " will still be updated.")
  @Default.Boolean(false)
  boolean getIsDryRun();

  void setIsDryRun(boolean value);
}
