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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.loadtest.LoadTestAction;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Registries;

/** Command to initiate a load-test. */
@Parameters(separators = " =", commandDescription = "Run a load test.")
class LoadTestCommand extends ConfirmingCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  // This is a mostly arbitrary value, roughly an hour and a quarter.  It served as a generous
  // timespan for initial backup/restore testing, but has no other special significance.
  private static final int DEFAULT_RUN_SECONDS = 4600;

  @Parameter(
      names = {"--tld"},
      description = "TLD that all records will be created under.")
  String tld = "example";

  @Parameter(
      names = {"--client_id"},
      description = "Client ID of the registrar that will own new records.")
  // "acme" is the id of the registrar we create in our public setup examples.
  String clientId = "acme";

  @Parameter(
      names = {"--successful_host_creates"},
      description = "Number of hosts to create per second.")
  int successfulHostCreates = 1;

  @Parameter(
      names = {"--successful_domain_creates"},
      description = "Number of domains to create per second.")
  int successfulDomainCreates = 1;

  @Parameter(
      names = {"--successful_contact_creates"},
      description = "Number of contact records to create per second.")
  int successfulContactCreates = 1;

  @Parameter(
      names = {"--host_infos"},
      description = "Number of successful host:info commands to send per second.")
  int hostInfos = 1;

  @Parameter(
      names = {"--domain_infos"},
      description = "Number of successful domain:info commands to send per second.")
  int domainInfos = 1;

  @Parameter(
      names = {"--contact_infos"},
      description = "Number of successful contact:info commands to send per second.")
  int contactInfos = 1;

  @Parameter(
      names = {"--run_seconds"},
      description = "Time to run the load test in seconds.")
  int runSeconds = DEFAULT_RUN_SECONDS;

  private AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @Override
  protected boolean checkExecutionState() {
    if (RegistryToolEnvironment.get() == RegistryToolEnvironment.PRODUCTION) {
      System.err.println("You may not run a load test against production.");
      return false;
    }

    // Check validity of TLD and Client Id.
    if (!Registries.getTlds().contains(tld)) {
      System.err.printf("No such TLD: %s\n", tld);
      return false;
    }
    if (!Registrar.loadByRegistrarId(clientId).isPresent()) {
      System.err.printf("No such client: %s\n", clientId);
      return false;
    }

    return true;
  }

  @Override
  protected String prompt() {
    return String.format(
        "Run the load test (TLD = %s, Registry = %s, env = %s)?",
        tld, clientId, RegistryToolEnvironment.get());
  }

  @Override
  protected String execute() throws Exception {
    System.err.println("Initiating load test...");

    ImmutableMap<String, Object> params = new ImmutableMap.Builder<String, Object>()
        .put("tld", tld)
        .put("clientId", clientId)
        .put("successfulHostCreates", successfulHostCreates)
        .put("successfulDomainCreates", successfulDomainCreates)
        .put("successfulContactCreates", successfulContactCreates)
        .put("hostInfos", hostInfos)
        .put("domainInfos", domainInfos)
        .put("contactInfos", contactInfos)
        .put("runSeconds", runSeconds)
        .build();

    return connection.sendPostRequest(
        LoadTestAction.PATH, params, MediaType.PLAIN_TEXT_UTF_8, new byte[0]);
  }
}
