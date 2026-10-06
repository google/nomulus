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

import static google.registry.util.CollectionUtils.nullToEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.net.InetAddresses;
import google.registry.model.eppinput.EppInput;
import google.registry.model.host.HostCommand;
import google.registry.util.DomainNameUtils;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.List;

/** A command to create a new host via EPP. */
@Parameters(separators = " =", commandDescription = "Create a new host via EPP.")
final class CreateHostCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as.",
      required = true)
  String clientId;

  @Parameter(
      names = "--host",
      description = "Host name.",
      required = true)
  private String hostName;

  @Parameter(
      names = {"-a", "--addresses"},
      description = "List of addresses in IPv4 and/or IPv6 format.",
      variableArity = true)
  private List<String> addresses;

  @Override
  protected void initMutatingEppToolCommand() {
    ImmutableSet.Builder<InetAddress> inetAddresses = new ImmutableSet.Builder<>();
    for (String address : nullToEmpty(addresses)) {
      inetAddresses.add(InetAddresses.forString(address));
    }

    HostCommand.Create.Builder createBuilder = new HostCommand.Create.Builder();
    createBuilder.setTargetId(DomainNameUtils.canonicalizeHostname(hostName));
    createBuilder.setInetAddresses(
        ImmutableSortedSet.copyOf(
            Comparator.comparing(InetAddresses::toAddrString), inetAddresses.build()));

    addEppInput(
        clientId,
        EppInput.create(EppInput.Create.create(createBuilder.build())).withClTrid("RegistryTool"));
  }
}
