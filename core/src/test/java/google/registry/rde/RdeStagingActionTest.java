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

package google.registry.rde;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.RdeFixtures.makeContactResource;
import static google.registry.rde.RdeFixtures.makeDomainBase;
import static google.registry.rde.RdeFixtures.makeHostResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResourceWithCommitLog;
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static google.registry.testing.TaskQueueHelper.assertAtLeastOneTaskIsEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.tldconfig.idn.IdnTableEnum.EXTENDED_LATIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertThrows;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.ListItem;
import com.google.appengine.tools.cloudstorage.ListOptions;
import com.google.appengine.tools.cloudstorage.ListResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.keyring.api.Keyring;
import google.registry.keyring.api.PgpHelper;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.RequestParameters;
import google.registry.schema.cursor.CursorDao;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import google.registry.util.TaskQueueUtils;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rde.XjcRdeContentType;
import google.registry.xjc.rde.XjcRdeDeposit;
import google.registry.xjc.rde.XjcRdeDepositTypeType;
import google.registry.xjc.rdedomain.XjcRdeDomain;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderCount;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdeidn.XjcRdeIdn;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xml.XmlException;
import google.registry.xml.XmlTestUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.bind.JAXBElement;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeStagingAction}. */
@RunWith(JUnit4.class)
public class RdeStagingActionTest extends MapreduceTestCase<RdeStagingAction> {

  private static final GcsFilename XML_FILE =
      new GcsFilename("rde-bucket", "lol_2000-01-01_full_S1_R0.xml.ghostryde");
  private static final GcsFilename LENGTH_FILE =
      new GcsFilename("rde-bucket", "lol_2000-01-01_full_S1_R0.xml.length");

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock();
  private final FakeResponse response = new FakeResponse();
  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final List<? super XjcRdeContentType> alreadyExtracted = new ArrayList<>();

  private static PGPPublicKey encryptKey;
  private static PGPPrivateKey decryptKey;

  @BeforeClass
  public static void beforeClass() {
    try (Keyring keyring = new FakeKeyringModule().get()) {
      encryptKey = keyring.getRdeStagingEncryptionKey();
      decryptKey = keyring.getRdeStagingDecryptionKey();
    }
  }

  @Before
  public void setup() {
    inject.setStaticField(Ofy.class, "clock", clock);
    action = new RdeStagingAction();
    action.clock = clock;
    action.mrRunner = makeDefaultRunner();
    action.lenient = false;
    action.reducerFactory = new RdeStagingReducer.Factory();
    action.reducerFactory.taskQueueUtils = new TaskQueueUtils(new Retrier(new SystemSleeper(), 1));
    action.reducerFactory.lockHandler = new FakeLockHandler(true);
    action.reducerFactory.gcsBufferSize = 0;
    action.reducerFactory.bucket = "rde-bucket";
    action.reducerFactory.lockTimeout = Duration.standardHours(1);
    action.reducerFactory.stagingKeyBytes = PgpHelper.convertPublicKeyToBytes(encryptKey);
    action.pendingDepositChecker = new PendingDepositChecker();
    action.pendingDepositChecker.brdaDayOfWeek = DateTimeConstants.TUESDAY;
    action.pendingDepositChecker.brdaInterval = Duration.standardDays(7);
    action.pendingDepositChecker.clock = clock;
    action.pendingDepositChecker.rdeInterval = Duration.standardDays(1);
    action.response = response;
    action.transactionCooldown = Duration.ZERO;
    action.directory = Optional.empty();
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of();
    action.revision = Optional.empty();
  }

  @Test
  public void testRun_modeInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.modeStrings = ImmutableSet.of("full");
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testRun_tldInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.tlds = ImmutableSet.of("tld");
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testRun_watermarkInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testRun_revisionInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.revision = Optional.of(42);
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testRun_noTlds_returns204() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  public void testRun_tldWithoutEscrowEnabled_returns204() {
    createTld("lol");
    persistResource(Registry.get("lol").asBuilder().setEscrowEnabled(false).build());
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  public void testRun_tldWithEscrowEnabled_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("/_ah/pipeline/status.html?root=");
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  @Test
  public void testRun_withinTransactionCooldown_getsExcludedAndReturns204() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:04:59Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    assertNoTasksEnqueued("mapreduce");
  }

  @Test
  public void testRun_afterTransactionCooldown_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:05:00Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  @Test
  public void testManualRun_emptyMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_invalidMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full", "thing");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_emptyTld_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_emptyWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of();
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_nonDayStartWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01T01:36:45Z"));
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_invalidRevision_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01T00:00:00Z"));
    action.revision = Optional.of(-1);
    assertThrows(BadRequestException.class, action::run);
  }

  @Test
  public void testManualRun_validParameters_runsMapReduce() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("_ah/pipeline/status.html?root=");
    assertAtLeastOneTaskIsEnqueued("mapreduce");
  }

  @Test
  public void testMapReduce_bunchOfResources_headerHasCorrectCounts() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XjcRdeDeposit deposit =
        unmarshal(
            XjcRdeDeposit.class, Ghostryde.decode(readGcsFile(gcsService, XML_FILE), decryptKey));
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);

    assertThat(header.getTld()).isEqualTo("lol");
    assertThat(mapifyCounts(header))
        .containsExactly(
            RdeResourceType.CONTACT.getUri(),
            3L,
            RdeResourceType.DOMAIN.getUri(),
            1L,
            RdeResourceType.HOST.getUri(),
            2L,
            RdeResourceType.REGISTRAR.getUri(),
            2L,
            RdeResourceType.IDN.getUri(),
            (long) IdnTableEnum.values().length);
  }

  @Test
  public void testMapReduce_validHostResources_getPutInDeposit() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeHostResource(clock, "ns1.cat.lol", "feed::a:bee");
    makeHostResource(clock, "ns2.cat.lol", "3.1.33.7");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XjcRdeDeposit deposit =
        unmarshal(
            XjcRdeDeposit.class, Ghostryde.decode(readGcsFile(gcsService, XML_FILE), decryptKey));
    assertThat(deposit.getType()).isEqualTo(XjcRdeDepositTypeType.FULL);
    assertThat(deposit.getId()).isEqualTo(RdeUtil.timestampToId(DateTime.parse("2000-01-01TZ")));
    assertThat(deposit.getWatermark()).isEqualTo(DateTime.parse("2000-01-01TZ"));
    assertThat(deposit.getResend()).isEqualTo(0);

    XjcRdeHost host1 = extractAndRemoveContentWithType(XjcRdeHost.class, deposit);
    XjcRdeHost host2 = extractAndRemoveContentWithType(XjcRdeHost.class, deposit);
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);

    assertThat(asList(host1.getName(), host2.getName()))
        .containsExactly("ns1.cat.lol", "ns2.cat.lol");
    assertThat(asList(host1.getAddrs().get(0).getValue(), host2.getAddrs().get(0).getValue()))
        .containsExactly("feed::a:bee", "3.1.33.7");

    assertThat(header.getTld()).isEqualTo("lol");
    assertThat(mapifyCounts(header))
        .containsExactly(
            RdeResourceType.CONTACT.getUri(),
            0L,
            RdeResourceType.DOMAIN.getUri(),
            0L,
            RdeResourceType.HOST.getUri(),
            2L,
            RdeResourceType.REGISTRAR.getUri(),
            2L,
            RdeResourceType.IDN.getUri(),
            (long) IdnTableEnum.values().length);
  }

  @Test
  public void testMapReduce_defaultTestFixtureRegistrars_getPutInDeposit() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeHostResource(clock, "ns1.cat.lol", "feed::a:bee");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XjcRdeDeposit deposit =
        unmarshal(
            XjcRdeDeposit.class, Ghostryde.decode(readGcsFile(gcsService, XML_FILE), decryptKey));
    XjcRdeRegistrar registrar1 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
    XjcRdeRegistrar registrar2 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);

    assertThat(asList(registrar1.getName(), registrar2.getName()))
        .containsExactly("New Registrar", "The Registrar");
    assertThat(mapifyCounts(header)).containsEntry(RdeResourceType.REGISTRAR.getUri(), 2L);
  }

  @Test
  public void testMapReduce_sameDayRdeDeposit_advancesCursorToTomorrow() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");
    setCursor(Registry.get("lol"), RDE_STAGING, DateTime.parse("2000-01-01TZ"));
    setCursor(Registry.get("lol"), BRDA, DateTime.parse("2000-01-04TZ"));
    clock.setTo(DateTime.parse("2000-01-01TZ")); // Saturday
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_STAGING, Registry.get("lol")))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("2000-01-02TZ"));
    assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get("lol"))).now().getCursorTime())
        .isEqualTo(DateTime.parse("2000-01-04TZ"));
  }

  @Test
  public void testMapReduce_onBrdaDay_advancesBothCursors() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");
    setCursor(Registry.get("lol"), RDE_STAGING, DateTime.parse("2000-01-04TZ"));
    setCursor(Registry.get("lol"), BRDA, DateTime.parse("2000-01-04TZ"));
    clock.setTo(DateTime.parse("2000-01-04TZ")); // Tuesday
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_STAGING, Registry.get("lol")))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("2000-01-05TZ"));
    assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get("lol"))).now().getCursorTime())
        .isEqualTo(DateTime.parse("2000-01-11TZ"));
  }

  @Test
  public void testMapReduce_onBrdaDay_enqueuesBothTasks() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");
    setCursor(Registry.get("lol"), RDE_STAGING, DateTime.parse("2000-01-04TZ"));
    setCursor(Registry.get("lol"), BRDA, DateTime.parse("2000-01-04TZ"));
    clock.setTo(DateTime.parse("2000-01-04TZ")); // Tuesday
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    assertTasksEnqueued("rde-upload",
        new TaskMatcher()
            .url(RdeUploadAction.PATH)
            .param(RequestParameters.PARAM_TLD, "lol"));
    assertTasksEnqueued("brda",
        new TaskMatcher()
            .url(BrdaCopyAction.PATH)
            .param(RequestParameters.PARAM_TLD, "lol")
            .param(RdeModule.PARAM_WATERMARK, "2000-01-04T00:00:00.000Z"));
  }

  @Test
  public void testMapReduce_noEppResourcesAndWayInPast_depositsRegistrarsOnly() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("fop");
    setCursor(Registry.get("fop"), RDE_STAGING, DateTime.parse("1971-01-01TZ"));
    setCursor(Registry.get("fop"), BRDA, DateTime.parse("1971-01-05TZ"));

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    for (GcsFilename filename : asList(
        new GcsFilename("rde-bucket", "fop_1971-01-01_full_S1_R0.xml.ghostryde"),
        new GcsFilename("rde-bucket", "fop_1971-01-05_thin_S1_R0.xml.ghostryde"))) {
      XjcRdeDeposit deposit =
          unmarshal(
              XjcRdeDeposit.class, Ghostryde.decode(readGcsFile(gcsService, filename), decryptKey));
      XjcRdeRegistrar registrar1 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
      XjcRdeRegistrar registrar2 = extractAndRemoveContentWithType(XjcRdeRegistrar.class, deposit);
      XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);

      assertThat(asList(registrar1.getName(), registrar2.getName()))
          .containsExactly("New Registrar", "The Registrar");
      assertThat(mapifyCounts(header)).containsEntry(RdeResourceType.REGISTRAR.getUri(), 2L);
    }

    assertThat(
            ofy().load().key(Cursor.createKey(RDE_STAGING, Registry.get("fop"))).now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("1971-01-02TZ"));

    assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get("fop"))).now().getCursorTime())
        .isEqualTo(DateTime.parse("1971-01-12TZ"));
  }

  @Test
  public void testMapReduce_idnTables_goInDeposit() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("fop");
    makeDomainBase(clock, "fop");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    GcsFilename filename = new GcsFilename("rde-bucket", "fop_2000-01-01_full_S1_R0.xml.ghostryde");
    XjcRdeDeposit deposit =
        unmarshal(
            XjcRdeDeposit.class, Ghostryde.decode(readGcsFile(gcsService, filename), decryptKey));
    XjcRdeDomain domain = extractAndRemoveContentWithType(XjcRdeDomain.class, deposit);
    XjcRdeIdn firstIdn = extractAndRemoveContentWithType(XjcRdeIdn.class, deposit);
    XjcRdeHeader header = extractAndRemoveContentWithType(XjcRdeHeader.class, deposit);

    assertThat(domain.getIdnTableId()).isEqualTo("extended_latin");
    assertThat(firstIdn.getId()).isEqualTo("extended_latin");
    assertThat(firstIdn.getUrl()).isEqualTo(EXTENDED_LATIN.getTable().getUrl().toString());
    assertThat(firstIdn.getUrlPolicy()).isEqualTo(EXTENDED_LATIN.getTable().getPolicy().toString());
    assertThat(mapifyCounts(header))
        .containsEntry(RdeResourceType.IDN.getUri(), (long) IdnTableEnum.values().length);
  }

  @Test
  public void testMapReduce_withDomain_producesExpectedXml() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XmlTestUtils.assertXmlEquals(
        loadFile(getClass(), "testMapReduce_withDomain_producesExpectedXml.xml"),
        readXml("lol_2000-01-01_full_S1_R0.xml.ghostryde"),
        "deposit.contents.registrar.crDate",
        "deposit.contents.registrar.upDate");
  }

  @Test
  public void testMapReduce_withDomain_producesCorrectLengthFile() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    byte[] deposit = Ghostryde.decode(readGcsFile(gcsService, XML_FILE), decryptKey);
    assertThat(Integer.parseInt(new String(readGcsFile(gcsService, LENGTH_FILE), UTF_8)))
        .isEqualTo(deposit.length);
  }

  @Test
  public void testMapReduce_withDomain_producesReportXml() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XmlTestUtils.assertXmlEquals(
        loadFile(getClass(), "testMapReduce_withDomain_producesReportXml.xml"),
        readXml("lol_2000-01-01_full_S1_R0-report.xml.ghostryde"),
        "deposit.contents.registrar.crDate",
        "deposit.contents.registrar.upDate");
  }

  @Test
  public void testMapReduce_twoDomainsDifferentTlds_isolatesDomains() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("boggle");
    makeDomainBase(clock, "boggle");
    createTldWithEscrowEnabled("lol");
    makeDomainBase(clock, "lol");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    String boggleDeposit = readXml("boggle_2000-01-01_full_S1_R0.xml.ghostryde");
    assertThat(boggleDeposit).contains("love.boggle");
    assertThat(boggleDeposit).doesNotContain("love.lol");

    String lolDeposit = readXml("lol_2000-01-01_full_S1_R0.xml.ghostryde");
    assertThat(lolDeposit).contains("love.lol");
    assertThat(lolDeposit).doesNotContain("love.boggle");
  }

  @Test
  public void testMapReduce_twoHostsDifferentTlds_includedInBothTldDeposits() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("fop");
    makeHostResource(clock, "ns1.dein.fop", "a:fed::cafe");
    createTldWithEscrowEnabled("lol");
    makeHostResource(clock, "ns1.kuss.lol", "face::feed");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    String fopDeposit = readXml("fop_2000-01-01_full_S1_R0.xml.ghostryde");
    assertThat(fopDeposit).contains("ns1.dein.fop");
    assertThat(fopDeposit).contains("ns1.kuss.lol");

    String lolDeposit = readXml("lol_2000-01-01_full_S1_R0.xml.ghostryde");
    assertThat(lolDeposit).contains("ns1.dein.fop");
    assertThat(lolDeposit).contains("ns1.kuss.lol");
  }

  @Test
  public void testMapReduce_rewindCursor_resendsDepositAtHigherRevision() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("fop");
    makeHostResource(clock, "ns1.dein.fop", "a:fed::cafe");

    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    XjcRdeDeposit deposit = unmarshal(
        XjcRdeDeposit.class,
        readXml("fop_2000-01-01_full_S1_R0.xml.ghostryde").getBytes(UTF_8));
    assertThat(deposit.getResend()).isEqualTo(0);

    setCursor(Registry.get("fop"), RDE_STAGING, DateTime.parse("2000-01-01TZ"));
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    deposit = unmarshal(
        XjcRdeDeposit.class, readXml("fop_2000-01-01_full_S1_R1.xml.ghostryde").getBytes(UTF_8));
    assertThat(deposit.getResend()).isEqualTo(1);
  }

  @Test
  public void testMapReduce_brdaDeposit_doesntIncludeHostsOrContacts() throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    createTldWithEscrowEnabled("xn--q9jyb4c");
    makeHostResource(clock, "ns1.bofh.みんな", "dead:fed::cafe");
    makeContactResource(clock, "123-IRL", "raven", "edgar@allen.みんな");
    setCursor(Registry.get("xn--q9jyb4c"), RDE_STAGING, DateTime.parse("2000-01-04TZ"));
    setCursor(Registry.get("xn--q9jyb4c"), BRDA, DateTime.parse("2000-01-04TZ"));

    clock.setTo(DateTime.parse("2000-01-04TZ")); // Tuesday
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    String rdeDeposit = readXml("xn--q9jyb4c_2000-01-04_full_S1_R0.xml.ghostryde");
    assertThat(rdeDeposit).contains("<rdeHost:name>ns1.bofh.xn--q9jyb4c");
    assertThat(rdeDeposit).contains("<rdeContact:email>edgar@allen.みんな");

    String brdaDeposit = readXml("xn--q9jyb4c_2000-01-04_thin_S1_R0.xml.ghostryde");
    assertThat(brdaDeposit).doesNotContain("<rdeHost:name>ns1.bofh.xn--q9jyb4c");
    assertThat(brdaDeposit).doesNotContain("<rdeContact:email>edgar@allen.みんな");
  }

  @Test
  public void testMapReduce_catchUpCursor_doesPointInTime() throws Exception {
    // Do nothing on the first day.
    clock.setTo(DateTime.parse("1984-12-17T12:00Z"));
    createTldWithEscrowEnabled("lol");
    setCursor(Registry.get("lol"), RDE_STAGING, DateTime.parse("1984-12-18TZ"));

    // Create the host resource on the second day.
    clock.setTo(DateTime.parse("1984-12-18T12:00Z"));
    HostResource ns1 = makeHostResource(clock, "ns1.justine.lol", "feed::a:bee");

    // Modify it on the third day.
    clock.setTo(DateTime.parse("1984-12-19T12:00Z"));
    persistResourceWithCommitLog(
        ns1.asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("dead:beef::cafe")))
            .build());

    // It's now the future. Let's catch up that cursor.
    clock.setTo(DateTime.parse("1990-01-01TZ"));

    // First mapreduce shouldn't emit host because it didn't exist.
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    String firstDeposit = readXml("lol_1984-12-18_full_S1_R0.xml.ghostryde");
    assertThat(firstDeposit).doesNotContain("ns1.justine.lol");
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_STAGING, Registry.get("lol")))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-19TZ"));

    // Second mapreduce should emit the old version of host.
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    String secondDeposit = readXml("lol_1984-12-19_full_S1_R0.xml.ghostryde");
    assertThat(secondDeposit).contains("ns1.justine.lol");
    assertThat(secondDeposit).contains("feed::a:bee");
    assertThat(secondDeposit).doesNotContain("dead:beef::cafe");

    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_STAGING, Registry.get("lol")))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-20TZ"));

    // Third mapreduce emits current version of host.
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    String thirdDeposit = readXml("lol_1984-12-20_full_S1_R0.xml.ghostryde");
    assertThat(thirdDeposit).contains("ns1.justine.lol");
    assertThat(thirdDeposit).doesNotContain("feed::a:bee");
    assertThat(thirdDeposit).contains("dead:beef::cafe");
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_STAGING, Registry.get("lol")))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-21TZ"));
  }

  private void doManualModeMapReduceTest(int revision, ImmutableSet<String> tlds) throws Exception {
    clock.setTo(DateTime.parse("1999-12-31TZ"));
    for (String tld : tlds) {
      createTldWithEscrowEnabled(tld);
      makeDomainBase(clock, tld);
      setCursor(Registry.get(tld), RDE_STAGING, DateTime.parse("1999-01-01TZ"));
      setCursor(Registry.get(tld), BRDA, DateTime.parse("2001-01-01TZ"));
    }

    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full", "thin");
    action.tlds = tlds;
    action.watermarks =
        ImmutableSet.of(DateTime.parse("2000-01-01TZ"), DateTime.parse("2000-01-02TZ"));
    action.revision = Optional.of(revision);

    action.run();
    executeTasksUntilEmpty("mapreduce", clock);

    ListResult listResult =
        gcsService.list("rde-bucket", new ListOptions.Builder().setPrefix("manual/test").build());
    ImmutableSet<String> filenames =
        ImmutableList.copyOf(listResult).stream().map(ListItem::getName).collect(toImmutableSet());
    for (String tld : tlds) {
      assertThat(filenames)
          .containsAtLeast(
              "manual/test/" + tld + "_2000-01-01_full_S1_R" + revision + "-report.xml.ghostryde",
              "manual/test/" + tld + "_2000-01-01_full_S1_R" + revision + ".xml.ghostryde",
              "manual/test/" + tld + "_2000-01-01_full_S1_R" + revision + ".xml.length",
              "manual/test/" + tld + "_2000-01-01_thin_S1_R" + revision + ".xml.ghostryde",
              "manual/test/" + tld + "_2000-01-01_thin_S1_R" + revision + ".xml.length",
              "manual/test/" + tld + "_2000-01-02_full_S1_R" + revision + "-report.xml.ghostryde",
              "manual/test/" + tld + "_2000-01-02_full_S1_R" + revision + ".xml.ghostryde",
              "manual/test/" + tld + "_2000-01-02_full_S1_R" + revision + ".xml.length",
              "manual/test/" + tld + "_2000-01-02_thin_S1_R" + revision + ".xml.ghostryde",
              "manual/test/" + tld + "_2000-01-02_thin_S1_R" + revision + ".xml.length");

      assertThat(
              ofy()
                  .load()
                  .key(Cursor.createKey(RDE_STAGING, Registry.get(tld)))
                  .now()
                  .getCursorTime())
          .isEqualTo(DateTime.parse("1999-01-01TZ"));
      assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get(tld))).now().getCursorTime())
          .isEqualTo(DateTime.parse("2001-01-01TZ"));
    }
  }

  @Test
  public void testMapReduce_manualMode_generatesCorrectDepositsWithoutAdvancingCursors()
      throws Exception {
    doManualModeMapReduceTest(0, ImmutableSet.of("lol"));
    XmlTestUtils.assertXmlEquals(
        loadFile(getClass(), "testMapReduce_withDomain_producesExpectedXml.xml"),
        readXml("manual/test/lol_2000-01-01_full_S1_R0.xml.ghostryde"),
        "deposit.contents.registrar.crDate",
        "deposit.contents.registrar.upDate");
    XmlTestUtils.assertXmlEquals(
        loadFile(getClass(), "testMapReduce_withDomain_producesReportXml.xml"),
        readXml("manual/test/lol_2000-01-01_full_S1_R0-report.xml.ghostryde"),
        "deposit.contents.registrar.crDate",
        "deposit.contents.registrar.upDate");
  }

  @Test
  public void testMapReduce_manualMode_nonZeroRevisionAndMultipleTlds()
      throws Exception {
    doManualModeMapReduceTest(42, ImmutableSet.of("lol", "slug"));
  }

  private String readXml(String objectName) throws IOException, PGPException {
    GcsFilename file = new GcsFilename("rde-bucket", objectName);
    return new String(Ghostryde.decode(readGcsFile(gcsService, file), decryptKey), UTF_8);
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

  private static void createTldWithEscrowEnabled(final String tld) {
    createTld(tld);
    persistResource(Registry.get(tld).asBuilder().setEscrowEnabled(true).build());
  }

  private static ImmutableMap<String, Long> mapifyCounts(XjcRdeHeader header) {
    ImmutableMap.Builder<String, Long> builder = new ImmutableMap.Builder<>();
    for (XjcRdeHeaderCount count : header.getCounts()) {
      builder.put(count.getUri(), count.getValue());
    }
    return builder.build();
  }

  private void setCursor(
      final Registry registry, final CursorType cursorType, final DateTime value) {
    clock.advanceOneMilli();
    CursorDao.saveCursor(Cursor.create(cursorType, value, registry), registry.getTldStr());
  }

  public static <T> T unmarshal(Class<T> clazz, byte[] xml) throws XmlException {
    return XjcXmlTransformer.unmarshal(clazz, new ByteArrayInputStream(xml));
  }
}
