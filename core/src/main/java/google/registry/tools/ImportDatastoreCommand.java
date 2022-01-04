// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.Operation;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * Command that imports an earlier backup into Datastore.
 *
 * <p>This command is part of the Datastore restore process. Please refer to <a
 * href="http://playbooks/domain_registry/procedures/backup-restore-testing.md">the playbook</a> for
 * the entire process.
 */
@DeleteAfterMigration
@Parameters(separators = " =", commandDescription = "Imports a backup of the Datastore.")
public class ImportDatastoreCommand extends ConfirmingCommand {

  @Parameter(names = "--backup_url", description = "URL to the backup on GCS to be imported.")
  private String backupUrl;

  @Nullable
  @Parameter(
      names = "--kinds",
      description = "List of entity kinds to be imported. Default is to import all.")
  private List<String> kinds = ImmutableList.of();

  @Parameter(
      names = "--async",
      description = "If true, command will launch import operation and quit.")
  private boolean async;

  @Parameter(
      names = "--poll_interval",
      description =
          "Polling interval while waiting for completion synchronously. "
              + "Value is in ISO-8601 format, e.g., PT10S for 10 seconds.")
  private Duration pollingInterval = Duration.standardSeconds(30);

  @Parameter(
      names = "--confirm_production_import",
      description = "Set this option to 'PRODUCTION' to confirm import in production environment.")
  private String confirmProductionImport = "";

  @Inject DatastoreAdmin datastoreAdmin;

  @Override
  protected String execute() throws Exception {
    RegistryToolEnvironment currentEnvironment = RegistryToolEnvironment.get();

    // Extra confirmation for running in production
    checkArgument(
        !currentEnvironment.equals(RegistryToolEnvironment.PRODUCTION)
            || confirmProductionImport.equals("PRODUCTION"),
        "The confirm_production_import option must be set when restoring production environment.");

    Operation importOperation = datastoreAdmin.importBackup(backupUrl, kinds).execute();

    String statusCommand =
        String.format(
            "nomulus -e %s get_operation_status %s",
            Ascii.toLowerCase(currentEnvironment.name()), importOperation.getName());

    if (async) {
      return String.format(
          "Datastore import started. Run this command to check its progress:\n%s",
          statusCommand);
    }

    System.out.println(
        "Waiting for import to complete.\n"
            + "You may press Ctrl-C at any time, and use this command to check progress:\n"
            + statusCommand);
    while (importOperation.isProcessing()) {
      waitInteractively(pollingInterval);

      importOperation = datastoreAdmin.get(importOperation.getName()).execute();

      System.out.printf("\n%s\n", importOperation.getProgress());
    }
    return String.format(
        "\nDatastore import %s %s.",
        importOperation.getName(), importOperation.isSuccessful() ? "succeeded" : "failed");
  }

  @Override
  protected String prompt() {
    return "\nThis command is an intermediate step in the Datastore restore process.\n\n"
        + "Please read and understand the playbook entry at\n"
        + "    http://playbooks/domain_registry/procedures/backup-restore-testing.md\n"
        + "before proceeding.\n";
  }

  /** Prints dots to console at regular interval while waiting. */
  private static void waitInteractively(Duration pollingInterval) throws InterruptedException {
    int sleepSeconds = 2;
    long iterations = (pollingInterval.getStandardSeconds() + sleepSeconds - 1) / sleepSeconds;

    for (int i = 0; i < iterations; i++) {
      TimeUnit.SECONDS.sleep(sleepSeconds);
      System.out.print('.');
      System.out.flush();
    }
  }
}
