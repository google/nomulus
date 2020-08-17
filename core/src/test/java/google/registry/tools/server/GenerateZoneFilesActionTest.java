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

package google.registry.tools.server;

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.testing.FakeClock;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.net.InetAddress;
import java.util.Map;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Tests for {@link GenerateZoneFilesAction}. */
class GenerateZoneFilesActionTest extends MapreduceTestCase<GenerateZoneFilesAction> {

  private final GcsService gcsService = createGcsService();

  @Test
  void testGenerate() throws Exception {
    DateTime now = DateTime.now(DateTimeZone.UTC).withTimeAtStartOfDay();
    createTlds("tld", "com");

    ImmutableSet<InetAddress> ips =
        ImmutableSet.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"));
    HostResource host1 =
        persistResource(newHostResource("ns.foo.tld").asBuilder().addInetAddresses(ips).build());
    HostResource host2 =
        persistResource(newHostResource("ns.bar.tld").asBuilder().addInetAddresses(ips).build());

    ImmutableSet<VKey<HostResource>> nameservers =
        ImmutableSet.of(host1.createVKey(), host2.createVKey());
    // This domain will have glue records, because it has a subordinate host which is its own
    // nameserver. None of the other domains should have glue records, because their nameservers are
    // subordinate to different domains.
    persistResource(newDomainBase("bar.tld").asBuilder()
        .addNameservers(nameservers)
        .addSubordinateHost("ns.bar.tld")
        .build());
    persistResource(newDomainBase("foo.tld").asBuilder()
        .addSubordinateHost("ns.foo.tld")
        .build());
    persistResource(newDomainBase("ns-and-ds.tld").asBuilder()
        .addNameservers(nameservers)
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
        .build());
    persistResource(newDomainBase("ns-only.tld").asBuilder()
        .addNameservers(nameservers)
        .build());
    persistResource(newDomainBase("ns-only-client-hold.tld").asBuilder()
        .addNameservers(nameservers)
        .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
        .build());
    persistResource(newDomainBase("ns-only-pending-delete.tld").asBuilder()
        .addNameservers(nameservers)
        .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
        .build());
    persistResource(newDomainBase("ns-only-server-hold.tld").asBuilder()
        .addNameservers(nameservers)
        .setStatusValues(ImmutableSet.of(StatusValue.SERVER_HOLD))
        .build());
    // These should be ignored; contacts aren't in DNS, hosts need to be from the same tld and have
    // IP addresses, and domains need to be from the same TLD and have hosts (even in the case where
    // domains contain DS data).
    persistResource(newDomainBase("ds-only.tld").asBuilder()
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
        .build());
    persistActiveContact("ignored_contact");
    persistActiveHost("ignored.host.tld");  // No ips.
    persistActiveDomain("ignored_domain.tld");  // No hosts or DS data.
    persistResource(newHostResource("ignored.foo.com").asBuilder().addInetAddresses(ips).build());
    persistResource(newDomainBase("ignored.com")
        .asBuilder()
        .addNameservers(nameservers)
        .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
        .build());

    GenerateZoneFilesAction action = new GenerateZoneFilesAction();
    action.mrRunner = makeDefaultRunner();
    action.bucket = "zonefiles-bucket";
    action.gcsBufferSize = 123;
    action.datastoreRetention = standardDays(29);
    action.dnsDefaultATtl = Duration.standardSeconds(11);
    action.dnsDefaultNsTtl = Duration.standardSeconds(222);
    action.dnsDefaultDsTtl = Duration.standardSeconds(3333);
    action.clock = new FakeClock(now.plusMinutes(2));  // Move past the actions' 2 minute check.

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.<String, Object>of("tlds", ImmutableList.of("tld"), "exportTime", now));
    assertThat(response)
        .containsEntry("filenames", ImmutableList.of("gs://zonefiles-bucket/tld-" + now + ".zone"));
    assertThat(response).containsKey("mapreduceConsoleLink");
    assertThat(response.get("mapreduceConsoleLink").toString())
        .startsWith(
            "Mapreduce console: https://backend-dot-projectid.appspot.com"
                + "/_ah/pipeline/status.html?root=");

    executeTasksUntilEmpty("mapreduce");

    GcsFilename gcsFilename =
        new GcsFilename("zonefiles-bucket", String.format("tld-%s.zone", now));
    String generatedFile = new String(readGcsFile(gcsService, gcsFilename), UTF_8);
    // The generated file contains spaces and tabs, but the golden file contains only spaces, as
    // files with literal tabs irritate our build tools.
    Splitter splitter = Splitter.on('\n').omitEmptyStrings();
    Iterable<String> generatedFileLines = splitter.split(generatedFile.replaceAll("\t", " "));
    Iterable<String> goldenFileLines = splitter.split(loadFile(getClass(), "tld.zone"));
    // The first line needs to be the same as the golden file.
    assertThat(generatedFileLines.iterator().next()).isEqualTo(goldenFileLines.iterator().next());
    // The remaining lines can be in any order.
    assertThat(generatedFileLines).containsExactlyElementsIn(goldenFileLines);
  }
}
