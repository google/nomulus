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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.X509Utils.loadCertificate;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import google.registry.config.RegistryEnvironment;
import google.registry.model.OteAccountBuilder;
import google.registry.tools.params.PathParameter;
import google.registry.util.Clock;
import google.registry.util.StringGenerator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;

/** Composite command to set up OT&E TLDs and accounts. */
@Parameters(separators = " =", commandDescription = "Set up OT&E TLDs and registrars")
final class SetupOteCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  private static final int PASSWORD_LENGTH = 16;

  @Parameter(
      names = {"-r", "--registrar"},
      description = "The registrar client ID, consisting of 3-14 lowercase letters and numbers.",
      required = true)
  private String registrar;

  @Parameter(
      names = {"-a", "--ip_allow_list"},
      description = "Comma-separated list of IP addreses or CIDR ranges.",
      required = true)
  private List<String> ipAllowList = new ArrayList<>();

  @Parameter(
      names = {"--email"},
      description =
          "The registrar's account to use for console access. "
              + "Must be on the registry's G Suite domain.",
      required = true)
  private String email;

  @Parameter(
      names = {"-c", "--certfile"},
      description = "Full path to cert file in PEM format (best if on local storage).",
      validateWith = PathParameter.InputFile.class)
  private Path certFile;

  @Parameter(
      names = {"--overwrite"},
      description = "Whether to replace existing entities if we encounter any, instead of failing.")
  private boolean overwrite = false;

  @Inject
  @Named("base64StringGenerator")
  StringGenerator passwordGenerator;

  @Inject Clock clock;

  OteAccountBuilder oteAccountBuilder;
  String password;

  /** Run any pre-execute command checks */
  @Override
  protected void init() throws Exception {
    checkArgument(certFile != null, "Must specify exactly one client certificate file.");

    password = passwordGenerator.createString(PASSWORD_LENGTH);
    oteAccountBuilder =
        OteAccountBuilder.forRegistrarId(registrar)
            .addContact(email)
            .setPassword(password)
            .setIpAllowList(ipAllowList)
            .setReplaceExisting(overwrite);

      String asciiCert = MoreFiles.asCharSource(certFile, US_ASCII).read();
      // Don't wait for create_registrar to fail if it's a bad certificate file.
      loadCertificate(asciiCert);
      oteAccountBuilder.setCertificate(asciiCert, clock.nowUtc());
  }

  @Override
  protected String prompt() {
    ImmutableMap<String, String> registrarToTldMap = oteAccountBuilder.getRegistrarIdToTldMap();
    StringBuilder builder = new StringBuilder();
    builder.append("Creating TLDs:");
    registrarToTldMap.values().forEach(tld -> builder.append("\n    ").append(tld));
    builder.append("\nCreating registrars:");
    registrarToTldMap.forEach(
        (clientId, tld) ->
            builder.append(String.format("\n    %s (with access to %s)", clientId, tld)));
    builder.append("\nGiving contact access to these registrars:").append("\n    ").append(email);

    if (RegistryEnvironment.get() != RegistryEnvironment.SANDBOX
        && RegistryEnvironment.get() != RegistryEnvironment.UNITTEST) {
      builder.append(
          String.format(
              "\n\nWARNING: Running against %s environment. Are "
                  + "you sure you didn\'t mean to run this against sandbox (e.g. \"-e SANDBOX\")?",
              RegistryEnvironment.get()));
    }

    return builder.toString();
  }

  @Override
  public String execute() {
    ImmutableMap<String, String> clientIdToTld = oteAccountBuilder.buildAndPersist();

    StringBuilder output = new StringBuilder();

    output.append("Copy these usernames/passwords back into the onboarding bug:\n\n");
    clientIdToTld.forEach(
        (clientId, tld) -> {
          output.append(
              String.format("Login: %s\nPassword: %s\nTLD: %s\n\n", clientId, password, tld));
        });

    return output.toString();
  }
}
