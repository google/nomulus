// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import google.registry.config.RegistryConfig.Config;
import google.registry.privileges.secretmanager.SecretManagerClient.SecretManagerException;
import google.registry.privileges.secretmanager.SqlCredential;
import google.registry.privileges.secretmanager.SqlCredentialStore;
import google.registry.privileges.secretmanager.SqlUser;
import google.registry.privileges.secretmanager.SqlUser.RobotUser;
import google.registry.tools.params.PathParameter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.inject.Inject;

/**
 * Command to get a Cloud SQL credential in the Secret Manager.
 *
 * <p>The schema deployment process will use this command's output. Coordinate with <a
 * href="https://github.com/google/nomulus/release/cloudbuild-schema-deploy.yaml">the schema
 * deployment script</a> before making changes to the output.
 */
@Parameters(separators = " =", commandDescription = "Get the Cloud SQL Credential for a given user")
public class GetSqlCredentialCommand implements Command {

  @Inject SqlCredentialStore store;

  @Inject
  @Config("cloudSqlInstanceConnectionName")
  String cloudSqlInstanceConnectionName;

  @Parameter(names = "--user", description = "The Cloud SQL user.", required = true)
  private String user;

  @Parameter(
      names = {"-o", "--output"},
      description = "Name of output file for key data.",
      validateWith = PathParameter.OutputFile.class)
  private Path outputPath = null;

  @Inject
  GetSqlCredentialCommand() {}

  @Override
  public void run() throws Exception {
    SqlUser sqlUser = new RobotUser(SqlUser.RobotId.valueOf(Ascii.toUpperCase(user)));

    SqlCredential credential;
    try {
      credential = store.getCredential(sqlUser);
    } catch (SecretManagerException e) {
      System.out.println(e.getMessage());
      return;
    }

    // Output format is important. Check class level javadoc before making changes.
    String outputText =
        String.format(
            "%s %s %s", cloudSqlInstanceConnectionName, credential.login(), credential.password());

    if (outputPath == null) {
      System.out.print(outputText);
      return;
    }
    try (FileOutputStream out = new FileOutputStream(outputPath.toFile())) {
      out.write(outputText.getBytes(StandardCharsets.UTF_8));
    }
  }
}
