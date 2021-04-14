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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;

import com.beust.jcommander.Parameters;
import google.registry.keyring.api.Keyring;
import google.registry.keyring.kms.KmsKeyring;
import google.registry.privileges.secretmanager.KeyringSecretStore;
import java.util.Map;
import javax.inject.Inject;

/** Migrates secrets from the KMS keyring to the Secret Manager. */
@Parameters(
    separators = " =",
    commandDescription = "Migrate values of secrets in KmsKeyring to Secret Manager.")
public class MigrateKmsKeyringCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  @Inject Keyring keyring;

  @Inject KeyringSecretStore secretStore;

  Map<String, Runnable> migrationTasks;

  @Inject
  MigrateKmsKeyringCommand() {}

  @Override
  protected void init() {

    checkState(
        keyring instanceof KmsKeyring,
        "Expecting KmsKeyring, found %s",
        keyring.getClass().getSimpleName());

    migrationTasks = ((KmsKeyring) keyring).migrationPlan();
  }

  @Override
  protected boolean dontRunCommand() {
    return migrationTasks.isEmpty();
  }

  @Override
  protected String prompt() {
    if (migrationTasks.isEmpty()) {
      return "All keys are up to date.";
    }
    return String.format("Migrate %s keys?", migrationTasks.size());
  }

  @Override
  protected String execute() {
    int errors = 0;
    for (Map.Entry<String, Runnable> entry : migrationTasks.entrySet()) {
      try {
        entry.getValue().run();
      } catch (Exception e) {
        System.err.printf("Failed to migrate %s: %s", entry.getKey(), e.getMessage());
        errors++;
      }
    }
    return errors == 0 ? "Success!" : "Failed to migrate " + errors + "keys.";
  }
}
