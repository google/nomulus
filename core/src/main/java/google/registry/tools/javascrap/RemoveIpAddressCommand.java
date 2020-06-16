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

package google.registry.tools.javascrap;

import static google.registry.model.ofy.ObjectifyService.ofy;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.host.HostResource;
import google.registry.tools.MutatingEppToolCommand;
import google.registry.tools.params.PathParameter;
import google.registry.tools.soy.RemoveIpAddressSoyInfo;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command to remove external IP Addresses from HostResources identified by text file listing
 * resource ids, one per line.
 *
 * <p>Written for b/23757755 so we can clean up records with IP addresses that should always be
 * resolved by hostname.
 *
 * <p>The JSON file should contain a list of objects each of which has a "roid" attribute.
 */
@Parameters(separators = " =", commandDescription = "Remove all IP Addresses.")
public class RemoveIpAddressCommand extends MutatingEppToolCommand {
  public static String registrarId = "CharlestonRoad";

  @Parameter(names = "--roids_file",
             description = "Text file containing a list of HostResource roids to remove",
             required = true,
             validateWith = PathParameter.InputFile.class)
  private Path roidsFilePath;

  @Override
  protected void initMutatingEppToolCommand() throws Exception {
    List<String> roids = Files.readAllLines(roidsFilePath, UTF_8);

    for (String roid : roids) {
      // Look up the HostResource from its roid.
      HostResource host = ofy().load().type(HostResource.class).id(roid).now();
      if (host == null) {
        System.err.printf("Record for %s not found.\n", roid);
        continue;
      }

      ArrayList<SoyMapData> ipAddresses = new ArrayList<>();
      for (InetAddress address : host.getInetAddresses()) {
        SoyMapData dataMap = new SoyMapData(
            "address", address.getHostAddress(),
            "version", address instanceof Inet6Address ? "v6" : "v4");
        ipAddresses.add(dataMap);
      }

      // Build and execute the EPP command.
      setSoyTemplate(
          RemoveIpAddressSoyInfo.getInstance(), RemoveIpAddressSoyInfo.REMOVE_IP_ADDRESS);
      addSoyRecord(registrarId, new SoyMapData(
          "name", host.getHostName(),
          "ipAddresses", ipAddresses,
          "requestedByRegistrar", registrarId));
    }
  }
}
