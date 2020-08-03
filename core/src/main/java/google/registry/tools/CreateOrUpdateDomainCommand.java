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
import static google.registry.util.CollectionUtils.findDuplicates;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import google.registry.tools.params.NameserversParameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared base class for commands to create or update a Domain via EPP. */
abstract class CreateOrUpdateDomainCommand extends MutatingEppToolCommand {

  @Parameter(
    names = {"-c", "--client"},
    description = "Client identifier of the registrar to execute the command as",
    required = true
  )
  String clientId;

  @Parameter(description = "Names of the domains", required = true)
  private List<String> mainParameters;

  @Parameter(
      names = {"-n", "--nameservers"},
      description = "Comma-delimited list of nameservers, up to 13.",
      converter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  Set<String> nameservers = new HashSet<>();

  @Parameter(
    names = {"-r", "--registrant"},
    description = "Domain registrant."
  )
  String registrant;

  @Parameter(
    names = {"-a", "--admins"},
    description = "Comma-separated list of admin contacts."
  )
  List<String> admins = new ArrayList<>();

  @Parameter(
    names = {"-t", "--techs"},
    description = "Comma-separated list of technical contacts."
  )
  List<String> techs = new ArrayList<>();

  @Parameter(
    names = {"-p", "--password"},
    description = "Password."
  )
  String password;

  @Parameter(
      names = "--ds_records",
      description =
          "Comma-separated list of DS records. Each DS record is given as "
              + "<keyTag> <alg> <digestType> <digest>, in order, as it appears in the Zonefile.",
      converter = DsRecord.Converter.class)
  List<DsRecord> dsRecords = new ArrayList<>();

  Set<String> domains;

  @Override
  protected void initEppToolCommand() throws Exception {
    checkArgument(nameservers.size() <= 13, "There can be at most 13 nameservers.");

    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate arguments found: '%s'", duplicates);
    domains = ImmutableSet.copyOf(mainParameters);

    initMutatingEppToolCommand();
  }
}
