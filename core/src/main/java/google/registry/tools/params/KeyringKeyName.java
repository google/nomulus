// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.params;

/**
 * Names of all the keyrings we can save.
 *
 * <p>This is used in GetKeyringSecretCommand to select the secret to get. It can also be used in
 * any secret update command such as UpdateKmsKeyringCommand.
 */
public enum KeyringKeyName {
  BRDA_RECEIVER_PUBLIC_KEY,
  BRDA_SIGNING_KEY_PAIR,
  BRDA_SIGNING_PUBLIC_KEY,
  ICANN_REPORTING_PASSWORD,
  JSON_CREDENTIAL,
  MARKSDB_DNL_LOGIN_AND_PASSWORD,
  MARKSDB_LORDN_PASSWORD,
  MARKSDB_SMDRL_LOGIN_AND_PASSWORD,
  RDE_RECEIVER_PUBLIC_KEY,
  RDE_SIGNING_KEY_PAIR,
  RDE_SIGNING_PUBLIC_KEY,
  RDE_SSH_CLIENT_PRIVATE_KEY,
  RDE_SSH_CLIENT_PUBLIC_KEY,
  RDE_STAGING_KEY_PAIR,
  RDE_STAGING_PUBLIC_KEY,
  SAFE_BROWSING_API_KEY
}
