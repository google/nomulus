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

package google.registry.model.tld;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.EntityYamlUtils.createObjectMapper;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.model.tld.Tld.TldState.QUIET_PERIOD;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static java.math.RoundingMode.UNNECESSARY;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld.TldNotFoundException;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.tld.label.ReservedList;
import google.registry.persistence.VKey;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.SerializeUtils;
import java.math.BigDecimal;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Tld}. */
public final class TldTest extends EntityTestCase {

  TldTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testPersistence_updateReservedAndPremiumListSuccessfully() {
    ReservedList rl15 = persistReservedList("tld-reserved15", "potato,FULLY_BLOCKED");
    PremiumList pl = persistPremiumList("tld2", USD, "lol,USD 50", "cat,USD 700");
    Tld registry = Tld.get("tld").asBuilder().setReservedLists(rl15).setPremiumList(pl).build();
    tm().transact(() -> tm().put(registry));
    Tld persisted = tm().transact(() -> tm().loadByKey(Tld.createVKey(registry.tldStr)));
    assertThat(persisted).isEqualTo(registry);
  }

  @Test
  void testPersistence() {
    assertWithMessage("Registry not found").that(Tld.get("tld")).isNotNull();
    assertThat(tm().transact(() -> tm().loadByKey(Tld.createVKey("tld"))))
        .isEqualTo(Tld.get("tld"));
  }

  @Test
  void testSerializable() {
    ReservedList rl15 = persistReservedList("tld-reserved15", "potato,FULLY_BLOCKED");
    Tld registry = Tld.get("tld").asBuilder().setReservedLists(rl15).build();
    tm().transact(() -> tm().put(registry));
    Tld persisted = tm().transact(() -> tm().loadByKey(Tld.createVKey(registry.tldStr)));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testTldToYaml() throws Exception {
    fakeClock.setTo(START_OF_TIME);
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    Tld existingTld =
        createTld("tld")
            .asBuilder()
            .setDnsAPlusAaaaTtl(Duration.standardHours(1))
            .setDnsWriters(ImmutableSet.of("baz", "bang"))
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 100),
                    DateTime.parse("2000-06-02T00:00:00Z"),
                    Money.of(USD, 0)))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("foo"))
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .setIdnTables(ImmutableSet.of(IdnTableEnum.JA, IdnTableEnum.EXTENDED_LATIN))
            .build();

    ObjectMapper mapper = createObjectMapper();
    String yaml = mapper.writeValueAsString(existingTld);
    assertThat(yaml).isEqualTo(loadFile(getClass(), "tld.yaml"));
  }

  @Test
  void testYamlToTld() throws Exception {
    fakeClock.setTo(START_OF_TIME);
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    Tld existingTld =
        createTld("tld")
            .asBuilder()
            .setDnsAPlusAaaaTtl(Duration.standardHours(1))
            .setDnsWriters(ImmutableSet.of("baz", "bang"))
            // set create billing cost back to the default (database helper sets it to $13)
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 100),
                    DateTime.parse("2000-06-02T00:00:00Z"),
                    Money.of(USD, 0)))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("foo"))
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .setIdnTables(ImmutableSet.of(IdnTableEnum.JA, IdnTableEnum.EXTENDED_LATIN))
            .build();

    ObjectMapper mapper = createObjectMapper();
    Tld constructedTld =
        mapper.readValue(readResourceBytes(getClass(), "tld.yaml").openBufferedStream(), Tld.class);
    compareTlds(existingTld, constructedTld);
  }

  @Test
  void testSuccess_tldYamlRoundtrip() throws Exception {
    Tld testTld = createTld("test");
    ObjectMapper mapper = createObjectMapper();
    String yaml = mapper.writeValueAsString(testTld);
    Tld constructedTld = mapper.readValue(yaml, Tld.class);
    compareTlds(testTld, constructedTld);
  }

  // On YAML serialization/deserialization some null values may be changed to empty collections
  void compareTlds(Tld existingTld, Tld constructedTld) {
    assertAboutImmutableObjects()
        .that(constructedTld)
        .isEqualExceptFields(
            existingTld,
            "dnsWriters",
            "idnTables",
            "reservedListNames",
            "allowedFullyQualifiedHostNames",
            "defaultPromoTokens");
    assertThat(constructedTld.getDnsWriters())
        .containsExactlyElementsIn(existingTld.getDnsWriters());
    assertThat(constructedTld.getIdnTables()).containsExactlyElementsIn(existingTld.getIdnTables());
    assertThat(constructedTld.getReservedListNames())
        .containsExactlyElementsIn(existingTld.getReservedListNames());
    assertThat(constructedTld.getAllowedFullyQualifiedHostNames())
        .containsExactlyElementsIn(existingTld.getAllowedFullyQualifiedHostNames());
    assertThat(constructedTld.getDefaultPromoTokens())
        .containsExactlyElementsIn(existingTld.getDefaultPromoTokens());
  }

  @Test
  void testFailure_registryNotFound() {
    createTld("foo");
    assertThrows(TldNotFoundException.class, () -> Tld.get("baz"));
  }

  @Test
  void testSettingEscrowEnabled_null() {
    assertThat(Tld.get("tld").asBuilder().setEscrowEnabled(true).build().getEscrowEnabled())
        .isTrue();
    assertThat(Tld.get("tld").asBuilder().setEscrowEnabled(false).build().getEscrowEnabled())
        .isFalse();
  }

  @Test
  void testSetCreateBillingCostTransitions() {
    ImmutableSortedMap<DateTime, Money> createCostTransitions =
        ImmutableSortedMap.of(
            START_OF_TIME,
            Money.of(USD, 8),
            fakeClock.nowUtc(),
            Money.of(USD, 1),
            fakeClock.nowUtc().plusMonths(1),
            Money.of(USD, 2),
            fakeClock.nowUtc().plusMonths(2),
            Money.of(USD, 3));
    Tld registry =
        Tld.get("tld").asBuilder().setCreateBillingCostTransitions(createCostTransitions).build();
    assertThat(registry.getCreateBillingCostTransitions()).isEqualTo(createCostTransitions);
    assertThat(registry.getCreateBillingCost(fakeClock.nowUtc().minus(Duration.standardDays(5))))
        .isEqualTo(Money.of(USD, 8));
    assertThat(registry.getCreateBillingCost(fakeClock.nowUtc().plusMonths(8)))
        .isEqualTo(Money.of(USD, 3));
  }

  @Test
  void testSetCreateBillingCostTransitionsNegativeCost() throws Exception {
    ImmutableSortedMap<DateTime, Money> createCostTransitions =
        ImmutableSortedMap.of(
            START_OF_TIME,
            Money.of(USD, 8),
            fakeClock.nowUtc(),
            Money.of(USD, 1),
            fakeClock.nowUtc().plusMonths(1),
            Money.of(USD, -2),
            fakeClock.nowUtc().plusMonths(2),
            Money.of(USD, 3));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setCreateBillingCostTransitions(createCostTransitions)
                    .build());
    assertThat(thrown.getMessage()).isEqualTo("Create billing cost cannot be negative");
  }

  @Test
  void testSettingRestoreBillingCost() {
    Tld registry = Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 42)).build();
    // The default value of 13 is set in createTld().
    assertThat(registry.getCreateBillingCost(fakeClock.nowUtc())).isEqualTo(Money.of(USD, 13));
    assertThat(registry.getRestoreBillingCost()).isEqualTo(Money.of(USD, 42));
  }

  @Test
  void testDefaultNumDnsPublishShards_equalToOne() {
    Tld registry = Tld.get("tld").asBuilder().build();
    assertThat(registry.getNumDnsPublishLocks()).isEqualTo(1);
  }

  @Test
  void testSettingNumDnsPublishShards() {
    Tld registry = Tld.get("tld").asBuilder().setNumDnsPublishLocks(2).build();
    assertThat(registry.getNumDnsPublishLocks()).isEqualTo(2);
  }

  @Test
  void testSetReservedList_doesntMutateExistingRegistry() {
    ReservedList rl15 =
        persistReservedList(
            new ReservedList.Builder()
                .setName("tld-reserved15")
                .setReservedListMapFromLines(
                    ImmutableList.of("potato,FULLY_BLOCKED", "phone,FULLY_BLOCKED"))
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    ReservedList rl16 =
        persistReservedList(
            new ReservedList.Builder()
                .setName("tld-reserved16")
                .setReservedListMapFromLines(
                    ImmutableList.of("port,FULLY_BLOCKED", "manteau,FULLY_BLOCKED"))
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    Tld registry1 =
        newTld("propter", "PROPTER").asBuilder().setReservedLists(ImmutableSet.of(rl15)).build();
    assertThat(registry1.getReservedListNames()).hasSize(1);
    Tld registry2 = registry1.asBuilder().setReservedLists(ImmutableSet.of(rl15, rl16)).build();
    assertThat(registry1.getReservedListNames()).hasSize(1);
    assertThat(registry2.getReservedListNames()).hasSize(2);
  }

  @Test
  void testGetReservedLists_doesntReturnNullWhenUninitialized() {
    Tld registry = newTld("foo", "FOO");
    assertThat(registry.getReservedListNames()).isNotNull();
    assertThat(registry.getReservedListNames()).isEmpty();
  }

  @Test
  void testGetAll() {
    createTld("foo");
    assertThat(Tld.get(ImmutableSet.of("foo", "tld")))
        .containsExactlyElementsIn(
            tm().transact(
                    () ->
                        tm().loadByKeys(
                                ImmutableSet.of(Tld.createVKey("foo"), Tld.createVKey("tld"))))
                .values());
  }

  @Test
  void testFailure_testGetAllMissingTld() {
    createTld("foo");
    TldNotFoundException thrown =
        assertThrows(
            TldNotFoundException.class,
            () -> assertThat(Tld.get(ImmutableSet.of("foo", "tld", "missing"))));
    assertThat(thrown).hasMessageThat().isEqualTo("No TLD object(s) found for missing");
  }

  @Test
  void testSetReservedLists() {
    ReservedList rl5 =
        persistReservedList(
            new ReservedList.Builder()
                .setName("tld-reserved5")
                .setReservedListMapFromLines(
                    ImmutableList.of("potato,FULLY_BLOCKED", "phone,FULLY_BLOCKED"))
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    ReservedList rl6 =
        persistReservedList(
            new ReservedList.Builder()
                .setName("tld-reserved6")
                .setReservedListMapFromLines(
                    ImmutableList.of("port,FULLY_BLOCKED", "manteau,FULLY_BLOCKED"))
                .setCreationTimestamp(fakeClock.nowUtc())
                .build());
    Tld r = Tld.get("tld").asBuilder().setReservedLists(ImmutableSet.of(rl5, rl6)).build();
    assertThat(r.getReservedListNames()).containsExactly("tld-reserved5", "tld-reserved6");
    r = Tld.get("tld").asBuilder().setReservedLists(ImmutableSet.of()).build();
    assertThat(r.getReservedListNames()).isEmpty();
  }

  @Test
  void testSetReservedListsByName() {
    persistReservedList(
        new ReservedList.Builder()
            .setName("tld-reserved15")
            .setReservedListMapFromLines(
                ImmutableList.of("potato,FULLY_BLOCKED", "phone,FULLY_BLOCKED"))
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    persistReservedList(
        new ReservedList.Builder()
            .setName("tld-reserved16")
            .setReservedListMapFromLines(
                ImmutableList.of("port,FULLY_BLOCKED", "manteau,FULLY_BLOCKED"))
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    Tld r =
        Tld.get("tld")
            .asBuilder()
            .setReservedListsByName(ImmutableSet.of("tld-reserved15", "tld-reserved16"))
            .build();
    assertThat(r.getReservedListNames()).containsExactly("tld-reserved15", "tld-reserved16");
    r = Tld.get("tld").asBuilder().setReservedListsByName(ImmutableSet.of()).build();
    assertThat(r.getReservedListNames()).isEmpty();
  }

  @Test
  void testSetPremiumList() {
    PremiumList pl2 = persistPremiumList("tld2", USD, "lol,USD 50", "cat,USD 700");
    Tld registry = Tld.get("tld").asBuilder().setPremiumList(pl2).build();
    Optional<String> pl = registry.getPremiumListName();
    assertThat(pl).hasValue("tld2");
    assertThat(PremiumListDao.getLatestRevision("tld2")).isPresent();
    assertThat(PremiumListDao.getLatestRevision("tld2").get().getName()).isEqualTo("tld2");
  }

  @Test
  void testSettingServerStatusChangeBillingCost() {
    Tld registry =
        Tld.get("tld").asBuilder().setServerStatusChangeBillingCost(Money.of(USD, 42)).build();
    assertThat(registry.getServerStatusChangeBillingCost()).isEqualTo(Money.of(USD, 42));
  }

  @Test
  void testSettingLordnUsername() {
    Tld registry = Tld.get("tld").asBuilder().setLordnUsername("username").build();
    assertThat(registry.getLordnUsername()).isEqualTo("username");
  }

  @Test
  void testSettingDnsWriters() {
    Tld registry = Tld.get("tld");
    assertThat(registry.getDnsWriters()).containsExactly(VoidDnsWriter.NAME);
    registry = registry.asBuilder().setDnsWriters(ImmutableSet.of("baz", "bang")).build();
    assertThat(registry.getDnsWriters()).containsExactly("baz", "bang");
  }

  @Test
  void testPdtLooksLikeGa() {
    Tld registry =
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, TldState.PDT))
            .build();
    assertThat(registry.getTldState(START_OF_TIME)).isEqualTo(GENERAL_AVAILABILITY);
  }

  @Test
  void testTldStateTransitionTimes() {
    Tld registry =
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.<DateTime, TldState>naturalOrder()
                    .put(START_OF_TIME, PREDELEGATION)
                    .put(fakeClock.nowUtc().plusMonths(1), START_DATE_SUNRISE)
                    .put(fakeClock.nowUtc().plusMonths(2), QUIET_PERIOD)
                    .put(fakeClock.nowUtc().plusMonths(3), GENERAL_AVAILABILITY)
                    .build())
            .build();
    assertThat(registry.getTldState(fakeClock.nowUtc())).isEqualTo(PREDELEGATION);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMillis(1))).isEqualTo(PREDELEGATION);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(1).minusMillis(1)))
        .isEqualTo(PREDELEGATION);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(1)))
        .isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(1).plusMillis(1)))
        .isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(2).minusMillis(1)))
        .isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(2))).isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(2).plusMillis(1)))
        .isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(3).minusMillis(1)))
        .isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(3)))
        .isEqualTo(GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(fakeClock.nowUtc().plusMonths(3).plusMillis(1)))
        .isEqualTo(GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(END_OF_TIME)).isEqualTo(GENERAL_AVAILABILITY);
  }

  @Test
  void testQuietPeriodCanAppearMultipleTimesAnywhere() {
    Tld.get("tld")
        .asBuilder()
        .setTldStateTransitions(
            ImmutableSortedMap.<DateTime, TldState>naturalOrder()
                .put(START_OF_TIME, PREDELEGATION)
                .put(fakeClock.nowUtc().plusMonths(1), QUIET_PERIOD)
                .put(fakeClock.nowUtc().plusMonths(2), START_DATE_SUNRISE)
                .put(fakeClock.nowUtc().plusMonths(3), QUIET_PERIOD)
                .put(fakeClock.nowUtc().plusMonths(6), GENERAL_AVAILABILITY)
                .build())
        .build();
  }

  @Test
  void testRenewBillingCostTransitionTimes() {
    Tld registry =
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 8),
                    fakeClock.nowUtc(),
                    Money.of(USD, 1),
                    fakeClock.nowUtc().plusMonths(1),
                    Money.of(USD, 2),
                    fakeClock.nowUtc().plusMonths(2),
                    Money.of(USD, 3)))
            .build();
    assertThat(registry.getStandardRenewCost(START_OF_TIME)).isEqualTo(Money.of(USD, 8));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().minusMillis(1)))
        .isEqualTo(Money.of(USD, 8));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc())).isEqualTo(Money.of(USD, 1));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMillis(1)))
        .isEqualTo(Money.of(USD, 1));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(1).minusMillis(1)))
        .isEqualTo(Money.of(USD, 1));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(1)))
        .isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(1).plusMillis(1)))
        .isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(2).minusMillis(1)))
        .isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(2)))
        .isEqualTo(Money.of(USD, 3));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(2).plusMillis(1)))
        .isEqualTo(Money.of(USD, 3));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMonths(3).minusMillis(1)))
        .isEqualTo(Money.of(USD, 3));
    assertThat(registry.getStandardRenewCost(END_OF_TIME)).isEqualTo(Money.of(USD, 3));
  }

  @Test
  void testRenewBillingCostNoTransitions() {
    Tld registry = Tld.get("tld");
    // The default value of 11 is set in createTld().
    assertThat(registry.getStandardRenewCost(START_OF_TIME)).isEqualTo(Money.of(USD, 11));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().minusMillis(1)))
        .isEqualTo(Money.of(USD, 11));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc())).isEqualTo(Money.of(USD, 11));
    assertThat(registry.getStandardRenewCost(fakeClock.nowUtc().plusMillis(1)))
        .isEqualTo(Money.of(USD, 11));
    assertThat(registry.getStandardRenewCost(END_OF_TIME)).isEqualTo(Money.of(USD, 11));
  }

  @Test
  void testFailure_tldNeverSet() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> new Tld.Builder().build());
    assertThat(thrown).hasMessageThat().contains("No registry TLD specified");
  }

  @Test
  void testFailure_setTldStr_null() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> new Tld.Builder().setTldStr(null));
    assertThat(thrown).hasMessageThat().contains("TLD must not be null");
  }

  @Test
  void testFailure_setTldStr_invalidTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Tld.Builder().setTldStr(".tld").build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot create registry for TLD that is not a valid, canonical domain name");
  }

  @Test
  void testFailure_setTldStr_nonCanonicalTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Tld.Builder().setTldStr("TLD").build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot create registry for TLD that is not a valid, canonical domain name");
  }

  @Test
  void testFailure_tldStatesOutOfOrder() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Tld.get("tld")
                .asBuilder()
                .setTldStateTransitions(
                    ImmutableSortedMap.of(
                        fakeClock.nowUtc(), GENERAL_AVAILABILITY,
                        fakeClock.nowUtc().plusMonths(1), START_DATE_SUNRISE))
                .build());
  }

  @Test
  void testFailure_duplicateTldState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Tld.get("tld")
                .asBuilder()
                .setTldStateTransitions(
                    ImmutableSortedMap.of(
                        fakeClock.nowUtc(), START_DATE_SUNRISE,
                        fakeClock.nowUtc().plusMonths(1), START_DATE_SUNRISE))
                .build());
  }

  @Test
  void testFailure_pricingEngineIsRequired() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Tld.Builder()
                    .setCreateBillingCostTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 13)))
                    .setTldStr("invalid")
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("All registries must have a configured pricing engine");
  }

  @Test
  void testFailure_negativeRenewBillingCostTransitionValue() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setRenewBillingCostTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, -42))));
    assertThat(thrown).hasMessageThat().contains("billing cost cannot be negative");
  }

  @Test
  void testFailure_negativeRestoreBillingCost() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, -42)));
    assertThat(thrown).hasMessageThat().contains("restoreBillingCost cannot be negative");
  }

  @Test
  void testFailure_nonPositiveNumDnsPublishLocks() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setNumDnsPublishLocks(-1));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "numDnsPublishLocks must be positive when set explicitly (use 1 for TLD-wide locks)");
    thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setNumDnsPublishLocks(0));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "numDnsPublishLocks must be positive when set explicitly (use 1 for TLD-wide locks)");
  }

  @Test
  void testFailure_negativeServerStatusChangeBillingCost() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setServerStatusChangeBillingCost(Money.of(USD, -42)));
    assertThat(thrown).hasMessageThat().contains("billing cost cannot be negative");
  }

  @Test
  void testFailure_renewBillingCostTransitionValue_wrongCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setRenewBillingCostTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 42)))
                    .build());
    assertThat(thrown).hasMessageThat().contains("cost must be in the TLD's currency");
  }

  @Test
  void testFailure_createBillingCost_wrongCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setCreateBillingCostTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 42)))
                    .build());
    assertThat(thrown).hasMessageThat().contains("cost must be in the TLD's currency");
  }

  @Test
  void testFailure_restoreBillingCost_wrongCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(EUR, 42)).build());
    assertThat(thrown).hasMessageThat().contains("cost must be in the TLD's currency");
  }

  @Test
  void testFailure_serverStatusChangeBillingCost_wrongCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setServerStatusChangeBillingCost(Money.of(EUR, 42))
                    .build());
    assertThat(thrown).hasMessageThat().contains("cost must be in the TLD's currency");
  }

  @Test
  void testEapFee_undefined() {
    assertThat(Tld.get("tld").getEapFeeFor(fakeClock.nowUtc()).getCost())
        .isEqualTo(BigDecimal.ZERO.setScale(2, UNNECESSARY));
  }

  @Test
  void testEapFee_specified() {
    DateTime a = fakeClock.nowUtc().minusDays(1);
    DateTime b = fakeClock.nowUtc().plusDays(1);
    Tld registry =
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 0),
                    a, Money.of(USD, 100),
                    b, Money.of(USD, 50)))
            .build();

    assertThat(registry.getEapFeeFor(fakeClock.nowUtc()).getCost())
        .isEqualTo(new BigDecimal("100.00"));
    assertThat(registry.getEapFeeFor(fakeClock.nowUtc().minusDays(2)).getCost())
        .isEqualTo(BigDecimal.ZERO.setScale(2, UNNECESSARY));
    assertThat(registry.getEapFeeFor(fakeClock.nowUtc().plusDays(2)).getCost())
        .isEqualTo(new BigDecimal("50.00"));
  }

  @Test
  void testFailure_eapFee_wrongCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Tld.get("tld")
                    .asBuilder()
                    .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
                    .build());
    assertThat(thrown).hasMessageThat().contains("All EAP fees must be in the TLD's currency");
  }

  @Test
  void testFailure_roidSuffixTooLong() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Tld.get("tld").asBuilder().setRoidSuffix("123456789"));
    assertThat(e).hasMessageThat().isEqualTo("ROID suffix must be in format ^[A-Z\\d_]{1,8}$");
  }

  @Test
  void testFailure_roidSuffixNotUppercased() {
    assertThrows(
        IllegalArgumentException.class, () -> Tld.get("tld").asBuilder().setRoidSuffix("abcd"));
  }

  @Test
  void testFailure_roidSuffixContainsInvalidCharacters() {
    assertThrows(
        IllegalArgumentException.class, () -> Tld.get("tld").asBuilder().setRoidSuffix("ABC-DEF"));
  }

  @Test
  void testSuccess_setDefaultPromoTokens() {
    Tld registry = Tld.get("tld");
    assertThat(registry.getDefaultPromoTokens()).isEmpty();
    AllocationToken token1 =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("abc123")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken token2 =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("token")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    ImmutableList<VKey<AllocationToken>> tokens =
        ImmutableList.of(token1.createVKey(), token2.createVKey());
    registry = registry.asBuilder().setDefaultPromoTokens(tokens).build();
    assertThat(registry.getDefaultPromoTokens()).isEqualTo(tokens);
  }

  @Test
  void testFailure_setDefaultPromoTokensWrongTokenType() {
    Tld registry = Tld.get("tld");
    assertThat(registry.getDefaultPromoTokens()).isEmpty();
    AllocationToken token1 =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry
                    .asBuilder()
                    .setDefaultPromoTokens(ImmutableList.of(token1.createVKey()))
                    .build());
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Token abc123 has an invalid token type of SINGLE_USE. DefaultPromoTokens must be of"
                + " the type DEFAULT_PROMO");
  }

  @Test
  void testFailure_setDefaultPromoTokensNotValidForTld() {
    Tld registry = Tld.get("tld");
    assertThat(registry.getDefaultPromoTokens()).isEmpty();
    AllocationToken token1 =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("abc123")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("example"))
                .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry
                    .asBuilder()
                    .setDefaultPromoTokens(ImmutableList.of(token1.createVKey()))
                    .build());
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The token abc123 is not valid for this TLD. The valid TLDs for it are [example]");
  }
}
