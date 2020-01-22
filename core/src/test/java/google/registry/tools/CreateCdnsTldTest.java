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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.ManagedZone;
import com.google.api.services.dns.model.ManagedZoneDnsSecConfig;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

/** Unit tests for {@link CreateCdnsTld}. */
public class CreateCdnsTldTest extends CommandTestCase<CreateCdnsTld> {

  @Mock Dns dnsService;
  @Mock Dns.ManagedZones managedZones;
  @Mock Dns.ManagedZones.Create request;
  @Captor ArgumentCaptor<String> projectId;
  @Captor ArgumentCaptor<ManagedZone> requestBody;

  @Before
  public void setUp() throws Exception {
    when(dnsService.managedZones()).thenReturn(managedZones);
    when(managedZones.create(projectId.capture(), requestBody.capture())).thenReturn(request);
    command = new CreateCdnsTld();
    command.projectId = "test-project";
    command.dnsService = dnsService;
  }

  private ManagedZone createZone(
      String nameServerSet, String description, String dnsName, String name) {
    return new ManagedZone()
        .setNameServerSet(nameServerSet)
        .setDnsName(dnsName)
        .setDescription(description)
        .setName(name)
        .setDnssecConfig(new ManagedZoneDnsSecConfig().setState("ON").setNonExistence("NSEC"));
  }

  @Test
  public void testBasicFunctionality() throws Exception {
    runCommand("--dns_name=tld.", "--name=tld", "--description=test run", "--force");
    verify(request).execute();
    assertThat(projectId.getValue()).isEqualTo("test-project");
    ManagedZone zone = requestBody.getValue();
    assertThat(zone).isEqualTo(createZone("cloud-dns-registry-test", "test run", "tld.", "tld"));
  }

  @Test
  public void testNameDefault() throws Exception {
    runCommand("--dns_name=tld.", "--description=test run", "--force");
    ManagedZone zone = requestBody.getValue();
    assertThat(zone).isEqualTo(createZone("cloud-dns-registry-test", "test run", "tld.", "tld."));
  }

  @Test
  public void testSandboxTldRestrictions() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandInEnvironment(RegistryToolEnvironment.SANDBOX, "--dns_name=foobar."));
    assertThat(thrown).hasMessageThat().contains("Sandbox TLDs must be of the form \"*.test.\"");
  }
}
