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
import google.registry.privileges.secretmanager.SqlCredentialStore;
import google.registry.privileges.secretmanager.SqlUser;
import google.registry.privileges.secretmanager.SqlUser.RobotUser;
import google.registry.tools.params.PathParameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;

/**
 * Command to create or update a Cloud SQL credential in the Secret Manager.
 *
 * <p>This command is a short-term tool that will be deprecated by the planned privilege server.
 */
@Parameters(
    separators = " =",
    commandDescription = "Create or update the Cloud SQL Credential for a given user")
public class SaveSqlCredentialCommand implements Command {

  @Inject SqlCredentialStore store;

  @Parameter(names = "--user", description = "The Cloud SQL user.", required = true)
  private String user;

  @Parameter(
      names = {"--input"},
      description = "Name of input file for the password.",
      validateWith = PathParameter.InputFile.class)
  private Path inputPath = null;

  @Inject
  SaveSqlCredentialCommand() {}

  @Override
  public void run() throws Exception {
    String password = Files.readAllLines(inputPath, StandardCharsets.UTF_8).get(0);
    SqlUser sqlUser = new RobotUser(SqlUser.RobotId.valueOf(Ascii.toUpperCase(user)));
    store.createOrUpdateCredential(sqlUser, password);
    System.out.println("Done");
  }
}
