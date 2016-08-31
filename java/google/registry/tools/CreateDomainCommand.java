// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static com.google.common.base.Strings.isNullOrEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.Command.GtechCommand;
import google.registry.tools.soy.DomainCreateSoyInfo;
import java.util.List;
import javax.inject.Inject;

/** A command to create a new domain via EPP. */
@Parameters(separators = " =", commandDescription = "Create a new domain via EPP.")
final class CreateDomainCommand extends MutatingEppToolCommand implements GtechCommand {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientIdentifier;

  @Parameter(
      names = "--domain",
      description = "Domain name",
      required = true)
  private String domain;

  @Parameter(
      names = "--period",
      description = "Initial registration period, in years.")
  private Integer period;

  @Parameter(
      names = {"-n", "--nameservers"},
      description = "List of nameservers, up to 13.",
      variableArity = true)
  private List<String> ns;

  @Parameter(
      names = {"-r", "--registrant"},
      description = "Domain registrant.",
      required = true)
  private String registrant;

  @Parameter(
      names = {"-a", "--admin"},
      description = "Admin contact.",
      required = true)
  private String admin;

  @Parameter(
      names = {"-t", "--tech"},
      description = "Technical contact.",
      required = true)
  private String tech;

  @Parameter(
      names = {"-p", "--password"},
      description = "Password. Optional, randomly generated if not provided.")
  private String password;

  @Inject
  PasswordGenerator passwordGenerator;

  private static final int PASSWORD_LENGTH = 16;

  @Override
  protected void initMutatingEppToolCommand() {
    if (isNullOrEmpty(password)) {
      password = passwordGenerator.createPassword(PASSWORD_LENGTH);
    }
    checkArgument(ns == null || ns.size() <= 13, "There can be at most 13 nameservers.");

    setSoyTemplate(DomainCreateSoyInfo.getInstance(), DomainCreateSoyInfo.DOMAINCREATE);
    addSoyRecord(clientIdentifier, new SoyMapData(
        "domain", domain,
        "period", period == null ? null : period.toString(),
        "ns", ns,
        "registrant", registrant,
        "admin", admin,
        "tech", tech,
        "password", password));
  }
}
