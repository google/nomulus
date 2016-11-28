// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.generateNewContactHostRoid;
import static google.registry.testing.DatastoreHelper.persistResourceWithCommitLog;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.config.TestRegistryConfig;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.rde.RdeCounter;
import google.registry.rde.RdeResourceType;
import google.registry.rde.RdeUtil;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.util.Idn;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rde.XjcRdeContentType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderCount;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xml.XmlException;
import google.registry.xml.XmlTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.JAXBElement;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link GenerateEscrowDepositCommand}. */
public class GenerateEscrowDepositCommandTest
    extends CommandTestCase<GenerateEscrowDepositCommand> {

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  private final FakeClock clock = new FakeClock(DateTime.parse("2010-10-17T04:20:00Z"));
  private final List<? super XjcRdeContentType> alreadyExtracted = new ArrayList<>();

  @Before
  public void before() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);
    command.encryptor = EncryptEscrowDepositCommandTest.createEncryptor();
    command.counter = new RdeCounter();
    command.eppResourceIndexBucketCount = new TestRegistryConfig().getEppResourceIndexBucketCount();
  }

  @Test
  public void testRun_randomDomain_generatesXmlAndEncryptsItToo() throws Exception {
    createTld("xn--q9jyb4c");
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--tld=xn--q9jyb4c",
        "--watermark=" + clock.nowUtc().withTimeAtStartOfDay());
    assertThat(tmpDir.getRoot().list()).asList().containsExactly(
        "xn--q9jyb4c_2010-10-17_full_S1_R0.xml",
        "xn--q9jyb4c_2010-10-17_full_S1_R0-report.xml",
        "xn--q9jyb4c_2010-10-17_full_S1_R0.ryde",
        "xn--q9jyb4c_2010-10-17_full_S1_R0.sig",
        "xn--q9jyb4c.pub");
  }

  @Test
  public void testRun_missingTldName_fails() throws Exception {
    thrown.expect(ParameterException.class);
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--watermark=" + clock.nowUtc());
  }

  @Test
  public void testRun_nonexistentTld_fails() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--tld=xn--q9jyb4c",
        "--watermark=" + clock.nowUtc());
  }

  @Test
  public void testRun_oneHostNotDeletedOrFuture_producesValidDepositXml() throws Exception {
    createTlds("lol", "ussr", "xn--unup4y");
    clock.setTo(DateTime.parse("1980-01-01TZ"));
    persistResourceWithCommitLog(
        newHostResource("communism.ussr", "dead:beef::cafe").asBuilder()
            .setDeletionTime(DateTime.parse("1991-12-25TZ"))  // nope you're deleted
            .build());
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    saveHostResource("ns1.cat.lol", "feed::a:bee");  // different tld doesn't matter
    clock.setTo(DateTime.parse("2020-12-31TZ"));
    saveHostResource("ns2.cat.lol", "a:fed::acab"); // nope you're from the future
    clock.setTo(DateTime.parse("2010-10-17TZ"));
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--tld=xn--unup4y",
        "--watermark=" + clock.nowUtc());
    XmlTestUtils.assertXmlEquals(
        readResourceUtf8(getClass(), "testdata/xn--unup4y_2010-10-17_full_S1_R0.xml"),
        new String(
            Files.readAllBytes(
                Paths.get(tmpDir.getRoot().toString(), "xn--unup4y_2010-10-17_full_S1_R0.xml")),
            UTF_8),
        "deposit.contents.registrar.crDate",
        "deposit.contents.registrar.upDate");
  }

  @Test
  public void testRun_generatedXml_isSchemaValidAndHasStuffInIt() throws Exception {
    clock.setTo(DateTime.parse("1984-12-17TZ"));
    createTld("xn--q9jyb4c");
    saveHostResource("ns1.cat.lol", "feed::a:bee");
    clock.setTo(DateTime.parse("1984-12-18TZ"));
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--tld=xn--q9jyb4c",
        "--watermark=" + clock.nowUtc());
    XjcRdeDeposit deposit = (XjcRdeDeposit)
        unmarshal(Files.readAllBytes(
            Paths.get(tmpDir.getRoot().toString(), "xn--q9jyb4c_1984-12-18_full_S1_R0.xml")));
    assertThat(deposit.getType()).isEqualTo(XjcRdeDepositTypeType.FULL);
    assertThat(deposit.getId()).isEqualTo(RdeUtil.timestampToId(DateTime.parse("1984-12-18TZ")));
    assertThat(deposit.getWatermark()).isEqualTo(DateTime.parse("1984-12-18TZ"));
    XjcRdeRegistrar registrar1 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
    XjcRdeRegistrar registrar2 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
    XjcRdeHost host = extractAndRemoveContentWithType(XjcRdeHost.class, deposit);
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);
    assertThat(host.getName()).isEqualTo("ns1.cat.lol");
    assertThat(asList(registrar1.getName(), registrar2.getName()))
        .containsExactly("New Registrar", "The Registrar");
    assertThat(mapifyCounts(header)).containsEntry(RdeResourceType.HOST.getUri(), 1L);
    assertThat(mapifyCounts(header)).containsEntry(RdeResourceType.REGISTRAR.getUri(), 2L);
  }

  @Test
  public void testRun_thinBrdaDeposit_hostsGetExcluded() throws Exception {
    clock.setTo(DateTime.parse("1984-12-17TZ"));
    createTld("xn--q9jyb4c");
    saveHostResource("ns1.cat.lol", "feed::a:bee");
    clock.setTo(DateTime.parse("1984-12-18TZ"));
    runCommand(
        "--outdir=" + tmpDir.getRoot(),
        "--tld=xn--q9jyb4c",
        "--watermark=" + clock.nowUtc(),
        "--mode=THIN");
    XjcRdeDeposit deposit = (XjcRdeDeposit)
        unmarshal(Files.readAllBytes(
            Paths.get(tmpDir.getRoot().toString(), "xn--q9jyb4c_1984-12-18_thin_S1_R0.xml")));
    assertThat(deposit.getType()).isEqualTo(XjcRdeDepositTypeType.FULL);
    assertThat(deposit.getId()).isEqualTo(RdeUtil.timestampToId(DateTime.parse("1984-12-18TZ")));
    assertThat(deposit.getWatermark()).isEqualTo(DateTime.parse("1984-12-18TZ"));
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);
    assertThat(mapifyCounts(header)).doesNotContainKey(RdeResourceType.HOST.getUri());
    assertThat(mapifyCounts(header)).containsEntry(RdeResourceType.REGISTRAR.getUri(), 2L);
  }

  private HostResource saveHostResource(String fqdn, String ip) {
    clock.advanceOneMilli();
    return persistResourceWithCommitLog(newHostResource(fqdn, ip));
  }

  private HostResource newHostResource(String fqdn, String ip) {
    return new HostResource.Builder()
        .setRepoId(generateNewContactHostRoid())
        .setCreationClientId("LawyerCat")
        .setCreationTimeForTest(clock.nowUtc())
        .setCurrentSponsorClientId("BusinessCat")
        .setFullyQualifiedHostName(Idn.toASCII(fqdn))
        .setInetAddresses(ImmutableSet.of(InetAddresses.forString(ip)))
        .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
        .setLastEppUpdateClientId("CeilingCat")
        .setLastEppUpdateTime(clock.nowUtc())
        .setStatusValues(ImmutableSet.of(
            StatusValue.OK,
            StatusValue.PENDING_UPDATE))
        .build();
  }

  public static Object unmarshal(byte[] xml) throws XmlException {
    return XjcXmlTransformer.unmarshal(Object.class, new ByteArrayInputStream(xml));
  }

  private static ImmutableMap<String, Long> mapifyCounts(XjcRdeHeader header) {
    ImmutableMap.Builder<String, Long> builder = new ImmutableMap.Builder<>();
    for (XjcRdeHeaderCount count : header.getCounts()) {
      builder.put(count.getUri(), count.getValue());
    }
    return builder.build();
  }

  private <T extends XjcRdeContentType>
      T extractAndRemoveContentWithType(Class<T> type, XjcRdeDeposit deposit) {
    for (JAXBElement<? extends XjcRdeContentType> content : deposit.getContents().getContents()) {
      XjcRdeContentType piece = content.getValue();
      if (type.isInstance(piece) && !alreadyExtracted.contains(piece)) {
        alreadyExtracted.add(piece);
        return type.cast(piece);
      }
    }
    throw new AssertionError("Expected deposit to contain another " + type.getSimpleName());
  }
}
