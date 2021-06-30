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

package google.registry.beam.rde;

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Description;

/** Custom options for running the spec11 pipeline. */
public interface RdePipelineOptions extends RegistryPipelineOptions {

  @Description("The Base64-encoded serialized map of TLDs to PendingDeposit.")
  String getPendings();

  void setPendings(String value);

  @Description("The validation mode (LENIENT|STRICT) that the RDE marshaller uses.")
  String getValidationMode();

  void setValidationMode(String value);

  @Description("The GCS bucket where the encrypted RDE deposits will be uploaded to.")
  String getGcsBucket();

  void setGcsBucket(String value);

  @Description("The Base64-encoded PGP public key to encrypt the deposits.")
  String getStagingKey();

  void setStagingKey(String value);
}
