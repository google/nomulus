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

import static com.google.common.base.Verify.verify;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.ManagedZone;
import com.google.api.services.dns.model.ManagedZoneDnsSecConfig;
import google.registry.config.RegistryConfig.Config;
import java.io.IOException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;

@Parameters(separators = " =", commandDescription = "Create a Managed Zone for a TLD in Cloud DNS.")
final class CreateCdnsTld extends ConfirmingCommand {

  @Parameter(names = "--description", description = "Description of the new TLD.")
  String description;

  @Parameter(
    names = "--dns_name",
    description = "DNS name of the new tld, including trailing period, e.g.: search.",
    required = true
  )
  String dnsName;

  @Nullable
  @Parameter(
    names = "--name",
    description = "Managed zone name.  If not specified, dns_name is used."
  )
  String name;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject Dns dnsService;

  private static final String KEY_VALUE_FORMAT = "  %s = %s";

  private ManagedZone managedZone;

  @Override
  protected void init() {
    // Sandbox talks to production Cloud DNS.  As a result, we can't configure any domains with a
    // suffix that might be used by customers on the same nameserver set.  Limit the user to setting
    // up *.test TLDs.
    if (RegistryToolEnvironment.get() == RegistryToolEnvironment.SANDBOX
        && !dnsName.endsWith(".test.")) {
      throw new IllegalArgumentException("Sandbox TLDs must be of the form \"*.test.\"");
    }

    managedZone =
        new ManagedZone()
            .setDescription(description)
            .setNameServerSet(
                RegistryToolEnvironment.get() == RegistryToolEnvironment.PRODUCTION
                ? "cloud-dns-registry"
                : "cloud-dns-registry-test")
            .setDnsName(dnsName)
            .setName((name != null) ? name : dnsName)
            .setDnssecConfig(new ManagedZoneDnsSecConfig().setNonExistence("nsec").setState("on"));
  }

  @Override
  protected String prompt() {
    return String.format(
        "Creating TLD with:\n%s\n%s",
        String.format(KEY_VALUE_FORMAT, "projectId", projectId),
        managedZone
            .entrySet()
            .stream()
            .map(entry -> String.format(KEY_VALUE_FORMAT, entry.getKey(), entry.getValue()))
            .collect(Collectors.joining("\n")));
  }

  @Override
  public String execute() throws IOException {
    validateDnsService();
    Dns.ManagedZones.Create request = dnsService.managedZones().create(projectId, managedZone);
    ManagedZone response = request.execute();
    return String.format("Created managed zone: %s", response);
  }

  private void validateDnsService() {
    // Sanity check to ensure only Production and Sandbox points to the CloudDns prod site.
    if (RegistryToolEnvironment.get() != RegistryToolEnvironment.PRODUCTION
        && RegistryToolEnvironment.get() != RegistryToolEnvironment.SANDBOX) {
      verify(!Dns.DEFAULT_ROOT_URL.equals(dnsService.getRootUrl()));
      verify(!Dns.DEFAULT_SERVICE_PATH.equals(dnsService.getServicePath()));
    }
  }
}
