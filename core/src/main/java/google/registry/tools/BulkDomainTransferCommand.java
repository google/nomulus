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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.batch.BulkDomainTransferAction;
import google.registry.model.registrar.Registrar;
import google.registry.util.DomainNameUtils;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A command to bulk-transfer any number of domains from one registrar to another.
 *
 * <p>This should be used as part of the BTAPPA (Bulk Transfer After a Partial Portfolio
 * Acquisition) process in order to transfer a (possibly large) list of domains from one registrar
 * to another, though it may be used in other situations as well.
 *
 * <p>For a true bulk transfer of domains, one should pass in a file with a list of domains (one per
 * line) but if we need to do an ad-hoc transfer of one domain we can do that as well.
 *
 * <p>For BTAPPA purposes, we expect "requestedByRegistrar" to be true; this may not be the case for
 * other purposes e.g. legal compliance transfers.
 */
@Parameters(
    separators = " =",
    commandDescription = "Transfer domain(s) in bulk with immediate effect.")
public class BulkDomainTransferCommand extends ConfirmingCommand implements CommandWithConnection {

  // we don't need any configuration on the Gson because all we need is a list of strings
  private static final Gson GSON = new Gson();
  private static final int DOMAIN_TRANSFER_BATCH_SIZE = 1000;

  @Parameter(
      names = {"--domains"},
      description =
          "Comma-separated list of domains to transfer, otherwise use --domain_names_file to"
              + " specify a possibly-large list of domains")
  private List<String> domains;

  @Parameter(
      names = {"-d", "--domain_names_file"},
      description = "A file with a list of newline-delimited domain names to create tokens for")
  private String domainNamesFile;

  @Parameter(
      names = {"-g", "--gaining_registrar_id"},
      description = "The ID of the registrar to which domains should be transferred",
      required = true)
  private String gainingRegistrarId;

  @Parameter(
      names = {"-l", "--losing_registrar_id"},
      description = "The ID of the registrar from which domains should be transferred",
      required = true)
  private String losingRegistrarId;

  @Parameter(
      names = {"--reason"},
      description = "Reason to transfer the domains",
      required = true)
  private String reason;

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.")
  private boolean requestedByRegistrar = false;

  @Parameter(
      names = {"--max_qps"},
      description =
          "Maximum queries to run per second, otherwise the default (maxQps) will be used")
  private int maxQps;

  private ServiceConnection connection;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  @Override
  protected String prompt() throws Exception {
    checkArgument(
        domainNamesFile != null ^ (domains != null && !domains.isEmpty()),
        "Must specify exactly one input method, either --domains or --domain_names_file");
    return String.format("Attempt to transfer %d domains?", getDomainList().size());
  }

  @Override
  protected String execute() throws Exception {
    checkArgument(
        Registrar.loadByRegistrarIdCached(gainingRegistrarId).isPresent(),
        "Gaining registrar %s doesn't exist",
        gainingRegistrarId);
    checkArgument(
        Registrar.loadByRegistrarIdCached(losingRegistrarId).isPresent(),
        "Losing registrar %s doesn't exist",
        losingRegistrarId);

    ImmutableMap.Builder<String, Object> paramsBuilder = new ImmutableMap.Builder<>();
    paramsBuilder.put("gainingRegistrarId", gainingRegistrarId);
    paramsBuilder.put("losingRegistrarId", losingRegistrarId);
    paramsBuilder.put("requestedByRegistrar", requestedByRegistrar);
    paramsBuilder.put("reason", reason);
    if (maxQps > 0) {
      paramsBuilder.put("maxQps", maxQps);
    }
    ImmutableMap<String, Object> params = paramsBuilder.build();

    for (List<String> batch : Iterables.partition(getDomainList(), DOMAIN_TRANSFER_BATCH_SIZE)) {
      System.out.printf("Sending batch of %d domains\n", batch.size());
      byte[] domainsList = GSON.toJson(batch).getBytes(UTF_8);
      System.out.println(
          connection.sendPostRequest(
              BulkDomainTransferAction.PATH, params, MediaType.PLAIN_TEXT_UTF_8, domainsList));
    }
    return "";
  }

  private ImmutableList<String> getDomainList() throws IOException {
    return domainNamesFile == null ? ImmutableList.copyOf(domains) : loadDomainsFromFile();
  }

  private ImmutableList<String> loadDomainsFromFile() throws IOException {
    return Splitter.on('\n')
        .omitEmptyStrings()
        .trimResults()
        .splitToStream(Files.asCharSource(new File(domainNamesFile), UTF_8).read())
        .map(DomainNameUtils::canonicalizeHostname)
        .collect(toImmutableList());
  }
}
