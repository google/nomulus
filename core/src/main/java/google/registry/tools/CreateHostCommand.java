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

import static google.registry.util.CollectionUtils.nullToEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.soy.HostCreateSoyInfo;
import google.registry.util.DomainNameUtils;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
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
    setSoyTemplate(HostCreateSoyInfo.getInstance(), HostCreateSoyInfo.HOSTCREATE);
    ImmutableList.Builder<String> ipv4Addresses = new ImmutableList.Builder<>();
    ImmutableList.Builder<String> ipv6Addresses = new ImmutableList.Builder<>();
    for (String address : nullToEmpty(addresses)) {
      InetAddress inetAddress = InetAddresses.forString(address);
      if (inetAddress instanceof Inet4Address) {
        ipv4Addresses.add(inetAddress.getHostAddress());
      } else if (inetAddress instanceof Inet6Address) {
        ipv6Addresses.add(inetAddress.getHostAddress());
      } else {
        throw new IllegalArgumentException(
            String.format("IP address in unknown format: %s", address));
      }
    }
    addSoyRecord(
        clientId,
        new SoyMapData(
            "hostname", DomainNameUtils.canonicalizeHostname(hostName),
            "ipv4addresses", ipv4Addresses.build(),
            "ipv6addresses", ipv6Addresses.build()));
  }
}
