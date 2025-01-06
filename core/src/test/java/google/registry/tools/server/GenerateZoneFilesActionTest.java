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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.gcs.GcsUtils;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.net.InetAddress;
import java.util.Map;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link GenerateZoneFilesAction}. */
class GenerateZoneFilesActionTest {

  @RegisterExtension
  public final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  @Test
  void testGenerate_defaultTtls() throws Exception {
    createTlds("tld", "com");
    testGenerate("tld.zone");
  }

  @Test
  void testGenerate_customTldTtls() throws Exception {
    createTlds("tld", "com");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDnsAPlusAaaaTtl(Duration.standardSeconds(300))
            .setDnsNsTtl(Duration.standardSeconds(400))
            .setDnsDsTtl(Duration.standardSeconds(500))
            .build());
    testGenerate("tldCustomTtl.zone");
  }

  @SuppressWarnings("AddressSelection")
  void testGenerate(String goldenFileName) throws Exception {
    DateTime now = DateTime.now(DateTimeZone.UTC).withTimeAtStartOfDay();

    ImmutableSet<InetAddress> ips =
        ImmutableSet.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"));
    Host host1 = persistResource(newHost("ns.foo.tld").asBuilder().addInetAddresses(ips).build());
    Host host2 = persistResource(newHost("ns.bar.tld").asBuilder().addInetAddresses(ips).build());

    ImmutableSet<VKey<Host>> nameservers = ImmutableSet.of(host1.createVKey(), host2.createVKey());
    // This domain will have glue records, because it has a subordinate host which is its own
    // nameserver. None of the other domains should have glue records, because their nameservers are
    // subordinate to different domains.
    persistResource(
        DatabaseHelper.newDomain("bar.tld")
            .asBuilder()
            .addNameservers(nameservers)
            .addSubordinateHost("ns.bar.tld")
            .build());
    persistResource(
        DatabaseHelper.newDomain("foo.tld").asBuilder().addSubordinateHost("ns.foo.tld").build());
    persistResource(
        DatabaseHelper.newDomain("ns-and-ds.tld")
            .asBuilder()
            .addNameservers(nameservers)
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build());
    persistResource(
        DatabaseHelper.newDomain("ns-only.tld").asBuilder().addNameservers(nameservers).build());
    persistResource(
        DatabaseHelper.newDomain("ns-only-client-hold.tld")
            .asBuilder()
            .addNameservers(nameservers)
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .build());
    persistResource(
        DatabaseHelper.newDomain("ns-only-pending-delete.tld")
            .asBuilder()
            .addNameservers(nameservers)
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    persistResource(
        DatabaseHelper.newDomain("ns-only-server-hold.tld")
            .asBuilder()
            .addNameservers(nameservers)
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_HOLD))
            .build());
    // These should be ignored; contacts aren't in DNS, hosts need to be from the same tld and have
    // IP addresses, and domains need to be from the same TLD and have hosts (even in the case where
    // domains contain DS data).
    persistResource(
        DatabaseHelper.newDomain("ds-only.tld")
            .asBuilder()
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build());
    persistActiveContact("ignored_contact");
    persistActiveHost("ignored.host.tld");  // No ips.
    persistActiveDomain("ignored_domain.tld");  // No hosts or DS data.
    persistResource(newHost("ignored.foo.com").asBuilder().addInetAddresses(ips).build());
    persistResource(
        DatabaseHelper.newDomain("ignored.com")
            .asBuilder()
            .addNameservers(nameservers)
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build());

    GenerateZoneFilesAction action = new GenerateZoneFilesAction();
    action.bucket = "zonefiles-bucket";
    action.gcsUtils = gcsUtils;
    action.databaseRetention = standardDays(29);
    action.dnsDefaultATtl = Duration.standardSeconds(11);
    action.dnsDefaultNsTtl = Duration.standardSeconds(222);
    action.dnsDefaultDsTtl = Duration.standardSeconds(3333);
    action.clock = new FakeClock(now.plusMinutes(2));  // Move past the actions' 2 minute check.

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.<String, Object>of("tlds", ImmutableList.of("tld"), "exportTime", now));
    assertThat(response)
        .containsEntry("filenames", ImmutableList.of("gs://zonefiles-bucket/tld-" + now + ".zone"));

    BlobId gcsFilename = BlobId.of("zonefiles-bucket", String.format("tld-%s.zone", now));
    String generatedFile = new String(gcsUtils.readBytesFrom(gcsFilename), UTF_8);
    // The generated file contains spaces and tabs, but the golden file contains only spaces, as
    // files with literal tabs irritate our build tools.
    Splitter splitter = Splitter.on('\n').omitEmptyStrings();
    Iterable<String> generatedFileLines = splitter.split(generatedFile.replaceAll("\t", " "));
    Iterable<String> goldenFileLines = splitter.split(loadFile(getClass(), goldenFileName));
    // The first line needs to be the same as the golden file.
    assertThat(generatedFileLines.iterator().next()).isEqualTo(goldenFileLines.iterator().next());
    // The remaining lines can be in any order.
    assertThat(generatedFileLines).containsExactlyElementsIn(goldenFileLines);
  }
}
