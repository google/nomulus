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

package google.registry.beam.billing;

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;

public interface ExpandBillingRecurrencesPipelineOptions extends RegistryPipelineOptions {
  @Description(
      "The inclusive lower bound of on the range of event times that will be expanded, in ISO 8601"
          + " format")
  String getStartTime();

  void setStartTime(String startTime);

  @Description(
      "The exclusive upper bound of on the range of event times that will be expanded, in ISO 8601"
          + " format")
  String getEndTime();

  void setEndTime(String endTime);

  @Description("If true, the expanded billing events and history entries will not be saved.")
  @Default.Boolean(false)
  boolean getIsDryRun();

  void setIsDryRun(boolean isDryRun);

  @Description(
      "If true, set the RECURRING_BILLING global cursor to endTime after saving all expanded"
          + " billing events and history entries.")
  @Default.Boolean(true)
  boolean getAdvanceCursor();

  void setAdvanceCursor(boolean advanceCursor);
}
