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

package google.registry.flows;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Sets.difference;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.EppXmlTransformer.marshal;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.BILLING_EVENT_ID_STRIPPER;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.flows.picker.FlowPicker;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tmch.ClaimsListShard.ClaimsListSingleton;
import google.registry.testing.AppEngineRule;
import google.registry.testing.EppLoader;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.testing.InjectRule;
import google.registry.testing.ShardableTestCase;
import google.registry.testing.TestDataHelper;
import google.registry.util.TypeUtils.TypeInstantiator;
import google.registry.xml.ValidationMode;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * Base class for resource flow unit tests.
 *
 * @param <F> the flow type
 */
@RunWith(MockitoJUnitRunner.class)
public abstract class FlowTestCase<F extends Flow> extends ShardableTestCase {

  /** Whether to actually write to the datastore or just simulate. */
  public enum CommitMode { LIVE, DRY_RUN }

  /** Whether to run in normal or superuser mode. */
  public enum UserPrivileges { NORMAL, SUPERUSER }

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final InjectRule inject = new InjectRule();

  protected EppLoader eppLoader;
  protected SessionMetadata sessionMetadata;
  protected FakeClock clock = new FakeClock(DateTime.now(UTC));
  protected TransportCredentials credentials = new PasswordOnlyTransportCredentials();
  protected EppRequestSource eppRequestSource = EppRequestSource.UNIT_TEST;

  @Before
  public void init() throws Exception {
    sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    sessionMetadata.setClientId("TheRegistrar");
    sessionMetadata.setServiceExtensionUris(ProtocolDefinition.getVisibleServiceExtensionUris());
    ofy().saveWithoutBackup().entity(new ClaimsListSingleton()).now();
    inject.setStaticField(Ofy.class, "clock", clock);  // For transactional flows.
  }

  protected void removeServiceExtensionUri(String uri) {
    sessionMetadata.setServiceExtensionUris(
        difference(sessionMetadata.getServiceExtensionUris(), ImmutableSet.of(uri)));
  }

  protected void setEppInput(String inputFilename) {
    eppLoader = new EppLoader(this, inputFilename, ImmutableMap.<String, String>of());
  }

  protected void setEppInput(String inputFilename, Map<String, String> substitutions) {
    eppLoader = new EppLoader(this, inputFilename, substitutions);
  }

  /** Returns the EPP data loaded by a previous call to setEppInput. */
  protected EppInput getEppInput() throws EppException {
    return eppLoader.getEpp();
  }

  protected String readFile(String filename) {
    return readResourceUtf8(getClass(), "testdata/" + filename);
  }

  protected String readFile(String filename, Map<String, String> substitutions) {
    return TestDataHelper.loadFileWithSubstitutions(getClass(), filename, substitutions);
  }

  protected String getClientTrid() throws Exception {
    return eppLoader.getEpp().getCommandWrapper().getClTrid();
  }

  /** Gets the client ID that the flow will run as. */
  protected String getClientIdForFlow() {
    return sessionMetadata.getClientId();
  }

  /** Sets the client ID that the flow will run as. */
  protected void setClientIdForFlow(String clientId) {
    sessionMetadata.setClientId(clientId);
  }

  public void assertTransactionalFlow(boolean isTransactional) throws Exception {
    Class<? extends Flow> flowClass = FlowPicker.getFlowClass(eppLoader.getEpp());
    if (isTransactional) {
      assertThat(flowClass).isAssignableTo(TransactionalFlow.class);
    } else {
      // There's no "isNotAssignableTo" in Truth.
      assertThat(TransactionalFlow.class.isAssignableFrom(flowClass))
          .named(flowClass.getSimpleName() + " implements TransactionalFlow")
          .isFalse();
    }
  }

  public void assertNoHistory() throws Exception {
    assertThat(ofy().load().type(HistoryEntry.class)).isEmpty();
  }

  public <T> T getOnlyGlobalResource(Class<T> clazz) throws Exception {
    return Iterables.getOnlyElement(ofy().load().type(clazz));
  }

  /** Helper to remove the grace period's billing event key to facilitate comparison. */
  private static final Function<GracePeriod, GracePeriod> GRACE_PERIOD_KEY_STRIPPER =
      new Function<GracePeriod, GracePeriod>() {
        @Override
        public GracePeriod apply(GracePeriod gracePeriod) {
          return GracePeriod.create(
              gracePeriod.isSunrushAddGracePeriod()
                  ? GracePeriodStatus.SUNRUSH_ADD
                  : gracePeriod.getType(),
              gracePeriod.getExpirationTime(),
              gracePeriod.getClientId(),
              null);
        }};

  /** A helper class that sets the billing event parent history entry to facilitate comparison. */
  public static class BillingEventParentSetter implements Function<BillingEvent, BillingEvent> {
    private HistoryEntry historyEntry;

    public static BillingEventParentSetter withParent(HistoryEntry historyEntry) {
      BillingEventParentSetter instance = new BillingEventParentSetter();
      instance.historyEntry = historyEntry;
      return instance;
    }

    @Override
    public BillingEvent apply(BillingEvent billingEvent) {
      return billingEvent.asBuilder().setParent(historyEntry).build();
    }

    private BillingEventParentSetter() {}
  }

  /**
   * Helper to facilitate comparison of maps of GracePeriods to BillingEvents.  This takes a map of
   * GracePeriods to BillingEvents and returns a map of the same entries that ignores the keys
   * on the grace periods and the IDs on the billing events (by setting them all to the same dummy
   * values), since they will vary between instantiations even when the other data is the same.
   */
  private ImmutableMap<GracePeriod, BillingEvent>
      canonicalizeGracePeriods(ImmutableMap<GracePeriod, ? extends BillingEvent> gracePeriods) {
    ImmutableMap.Builder<GracePeriod, BillingEvent> builder = new ImmutableMap.Builder<>();
    for (Map.Entry<GracePeriod, ? extends BillingEvent> entry : gracePeriods.entrySet()) {
      builder.put(
          GRACE_PERIOD_KEY_STRIPPER.apply(entry.getKey()),
          BILLING_EVENT_ID_STRIPPER.apply(entry.getValue()));
    }
    return builder.build();
  }

  /**
   * Assert that the actual grace periods and the corresponding billing events referenced from
   * their keys match the expected map of grace periods to billing events.  For the expected map,
   * the keys on the grace periods and IDs on the billing events are ignored.
   */
  public void assertGracePeriods(
      Iterable<GracePeriod> actual,
      ImmutableMap<GracePeriod, ? extends BillingEvent> expected) {
    Function<GracePeriod, BillingEvent> gracePeriodExpander =
        new Function<GracePeriod, BillingEvent>() {
          @Override
          public BillingEvent apply(GracePeriod gracePeriod) {
            assertThat(gracePeriod.hasBillingEvent())
                .named("Billing event is present for grace period: " + gracePeriod)
                .isTrue();
            return ofy().load()
                .key(firstNonNull(
                    gracePeriod.getOneTimeBillingEvent(),
                    gracePeriod.getRecurringBillingEvent()))
                .now();
          }};
    assertThat(canonicalizeGracePeriods(Maps.toMap(actual, gracePeriodExpander)))
        .isEqualTo(canonicalizeGracePeriods(expected));
  }

  public void assertPollMessages(
      String clientId,
      PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(clientId), expected);
  }

  public void assertPollMessages(
      String clientId,
      DateTime now,
      PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(clientId, now), expected);
  }

  public void assertPollMessages(PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(), expected);
  }

  /** Assert that the list matches all the poll messages in the fake datastore. */
  public void assertPollMessagesHelper(Iterable<PollMessage> pollMessages, PollMessage... expected)
      throws Exception {
    // To facilitate comparison, remove the ids.
    Function<PollMessage, PollMessage> idStripper =
        new Function<PollMessage, PollMessage>() {
          @Override
          public PollMessage apply(PollMessage pollMessage) {
            return pollMessage.asBuilder().setId(1L).build();
          }};
    // Ordering is irrelevant but duplicates should be considered independently.
    assertThat(FluentIterable.from(pollMessages).transform(idStripper))
        .containsExactlyElementsIn(FluentIterable.from(asList(expected)).transform(idStripper));
  }

  private EppOutput runFlowInternal(CommitMode commitMode, UserPrivileges userPrivileges)
      throws Exception {
    // Assert that the xml triggers the flow we expect.
    assertThat(FlowPicker.getFlowClass(eppLoader.getEpp()))
        .isEqualTo(new TypeInstantiator<F>(getClass()){}.getExactType());
    // Run the flow.
    return DaggerEppTestComponent.builder()
        .fakesAndMocksModule(new FakesAndMocksModule(clock, tmchCaMode))
        .build()
        .startRequest()
        .flowComponentBuilder()
            .flowModule(new FlowModule.Builder()
                .setSessionMetadata(sessionMetadata)
                .setCredentials(credentials)
                .setEppRequestSource(eppRequestSource)
                .setIsDryRun(commitMode.equals(CommitMode.DRY_RUN))
                .setIsSuperuser(userPrivileges.equals(UserPrivileges.SUPERUSER))
                .setInputXmlBytes(eppLoader.getEppXml().getBytes(UTF_8))
                .setEppInput(eppLoader.getEpp())
                .build())
            .build()
            .flowRunner()
            .run();
  }

  /** Run a flow and marshal the result to EPP, or throw if it doesn't validate. */
  public EppOutput runFlow(CommitMode commitMode, UserPrivileges userPrivileges) throws Exception {
    EppOutput output = runFlowInternal(commitMode, userPrivileges);
    marshal(output, ValidationMode.STRICT);
    return output;
  }

  /** Shortcut to call {@link #runFlow(CommitMode, UserPrivileges)} as normal user and live run. */
  public EppOutput runFlow() throws Exception {
    return runFlow(CommitMode.LIVE, UserPrivileges.NORMAL);
  }

  /** Run a flow, marshal the result to EPP, and assert that the output is as expected. */
  public EppOutput runFlowAssertResponse(
      CommitMode commitMode, UserPrivileges userPrivileges, String xml, String... ignoredPaths)
      throws Exception {
    // Always ignore the server trid, since it's generated and meaningless to flow correctness.
    // TODO(user): Remove asList()
    String[] ignoredPathsPlusTrid = FluentIterable.from(asList(ignoredPaths))
        .append("epp.response.trID.svTRID")
        .toArray(String.class);
    EppOutput output = runFlowInternal(commitMode, userPrivileges);
    if (output.isResponse()) {
      assertThat(output.isSuccess()).isTrue();
    }
    try {
      assertXmlEquals(
          xml, new String(marshal(output, ValidationMode.STRICT), UTF_8), ignoredPathsPlusTrid);
    } catch (Throwable e) {
      assertXmlEquals(
          xml, new String(marshal(output, ValidationMode.LENIENT), UTF_8), ignoredPathsPlusTrid);
      // If it was a marshaling error, augment the output.
      throw new Exception(
          String.format(
              "Invalid xml.\nExpected:\n%s\n\nActual:\n%s\n",
              xml,
              marshal(output, ValidationMode.LENIENT)),
          e);
    }
    // Clear the cache so that we don't see stale results in tests.
    ofy().clearSessionCache();
    return output;
  }

  private TmchCaMode tmchCaMode = TmchCaMode.PILOT;

  protected void useTmchProdCert() {
    tmchCaMode = TmchCaMode.PRODUCTION;
  }

  public EppOutput dryRunFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    List<Object> beforeEntities = ofy().load().list();
    EppOutput output =
        runFlowAssertResponse(CommitMode.DRY_RUN, UserPrivileges.NORMAL, xml, ignoredPaths);
    assertThat(ofy().load()).containsExactlyElementsIn(beforeEntities);
    return output;
  }

  public EppOutput runFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    return runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, xml, ignoredPaths);
  }
}
