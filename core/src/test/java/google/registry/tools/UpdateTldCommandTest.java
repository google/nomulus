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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.PREDELEGATION;
import static google.registry.model.registry.Registry.TldState.QUIET_PERIOD;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.registry.Registry;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateTldCommand}. */
class UpdateTldCommandTest extends CommandTestCase<UpdateTldCommand> {

  private final DateTime now = DateTime.now(UTC);

  @BeforeEach
  void beforeEach() {
    persistReservedList("common_abuse", "baa,FULLY_BLOCKED");
    persistReservedList("xn--q9jyb4c_abuse", "lamb,FULLY_BLOCKED");
    persistReservedList("tld_banned", "kilo,FULLY_BLOCKED", "lima,FULLY_BLOCKED");
    persistReservedList("soy_expurgated", "fireflies,FULLY_BLOCKED");
    persistPremiumList("xn--q9jyb4c", USD, "minecraft,USD 1000");
    persistReservedList("xn--q9jyb4c_r1", "foo,FULLY_BLOCKED");
    persistReservedList("xn--q9jyb4c_r2", "moop,FULLY_BLOCKED");
    createTld("xn--q9jyb4c");
    command.validDnsWriterNames = ImmutableSet.of("VoidDnsWriter", "FooDnsWriter");
  }

  @Test
  void testSuccess_tldStateTransitions() throws Exception {
    DateTime sunriseStart = now;
    DateTime quietPeriodStart = sunriseStart.plusMonths(2);
    DateTime gaStart = quietPeriodStart.plusWeeks(1);
    runCommandForced(
        String.format(
            "--tld_state_transitions=%s=PREDELEGATION,%s=START_DATE_SUNRISE,%s=QUIET_PERIOD,"
                + "%s=GENERAL_AVAILABILITY",
            START_OF_TIME, sunriseStart, quietPeriodStart, gaStart),
        "xn--q9jyb4c");

    Registry registry = Registry.get("xn--q9jyb4c");
    assertThat(registry.getTldState(sunriseStart.minusMillis(1))).isEqualTo(PREDELEGATION);
    assertThat(registry.getTldState(sunriseStart)).isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(sunriseStart.plusMillis(1))).isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(quietPeriodStart.minusMillis(1))).isEqualTo(START_DATE_SUNRISE);
    assertThat(registry.getTldState(quietPeriodStart)).isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(quietPeriodStart.plusMillis(1))).isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(gaStart.minusMillis(1))).isEqualTo(QUIET_PERIOD);
    assertThat(registry.getTldState(gaStart)).isEqualTo(GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(gaStart.plusMillis(1))).isEqualTo(GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(END_OF_TIME)).isEqualTo(GENERAL_AVAILABILITY);
  }

  @Test
  void testSuccess_setTldState() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, PREDELEGATION))
            .build());
    runCommandForced("--set_current_tld_state=START_DATE_SUNRISE", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getTldState(now.plusDays(1)))
        .isEqualTo(START_DATE_SUNRISE);
  }

  @Test
  void testSuccess_renewBillingCostTransitions() throws Exception {
    DateTime later = now.plusMonths(1);
    runCommandForced(
        String.format(
            "--renew_billing_cost_transitions=\"%s=USD 1,%s=USD 2.00,%s=USD 100\"",
            START_OF_TIME,
            now,
            later),
        "xn--q9jyb4c");

    Registry registry = Registry.get("xn--q9jyb4c");
    assertThat(registry.getStandardRenewCost(START_OF_TIME)).isEqualTo(Money.of(USD, 1));
    assertThat(registry.getStandardRenewCost(now.minusMillis(1))).isEqualTo(Money.of(USD, 1));
    assertThat(registry.getStandardRenewCost(now)).isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(now.plusMillis(1))).isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(later.minusMillis(1))).isEqualTo(Money.of(USD, 2));
    assertThat(registry.getStandardRenewCost(later)).isEqualTo(Money.of(USD, 100));
    assertThat(registry.getStandardRenewCost(later.plusMillis(1))).isEqualTo(Money.of(USD, 100));
    assertThat(registry.getStandardRenewCost(END_OF_TIME)).isEqualTo(Money.of(USD, 100));
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));
    createTld("example");
    assertThat(Registry.get("example").getAddGracePeriodLength()).isNotEqualTo(standardMinutes(5));

    runCommandForced("--add_grace_period=PT300S", "xn--q9jyb4c", "example");

    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
    assertThat(Registry.get("example").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_addGracePeriodFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));
    runCommandForced("--add_grace_period=PT300S", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_redemptionGracePeriodFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getRedemptionGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));
    runCommandForced("--redemption_grace_period=PT300S", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getRedemptionGracePeriodLength())
        .isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_pendingDeleteLengthFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getPendingDeleteLength())
        .isNotEqualTo(standardMinutes(5));
    runCommandForced("--pending_delete_length=PT300S", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPendingDeleteLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_dnsWriter() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getDnsWriters()).containsExactly("VoidDnsWriter");
    runCommandForced("--dns_writers=FooDnsWriter", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDnsWriters()).containsExactly("FooDnsWriter");
  }

  @Test
  void testSuccess_multipleDnsWriters() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getDnsWriters()).containsExactly("VoidDnsWriter");

    runCommandForced("--dns_writers=FooDnsWriter,VoidDnsWriter", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDnsWriters())
        .containsExactly("FooDnsWriter", "VoidDnsWriter");
  }

  @Test
  void testSuccess_escrow() throws Exception {
    runCommandForced("--escrow=true", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getEscrowEnabled()).isTrue();
  }

  @Test
  void testSuccess_noEscrow() throws Exception {
    runCommandForced("--escrow=false", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getEscrowEnabled()).isFalse();
  }

  @Test
  void testSuccess_createBillingCostFlag() throws Exception {
    runCommandForced("--create_billing_cost=\"USD 42.42\"", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getStandardCreateCost()).isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_restoreBillingCostFlag() throws Exception {
    runCommandForced("--restore_billing_cost=\"USD 42.42\"", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getStandardRestoreCost())
        .isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_nonUsdBillingCostFlag() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c")
            .asBuilder()
            .setCurrency(JPY)
            .setCreateBillingCost(Money.ofMajor(JPY, 1))
            .setRestoreBillingCost(Money.ofMajor(JPY, 1))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 1)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(JPY)))
            .setServerStatusChangeBillingCost(Money.ofMajor(JPY, 1))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(JPY, 1))
            .build());
    runCommandForced(
        "--create_billing_cost=\"JPY 12345\"",
        "--restore_billing_cost=\"JPY 67890\"",
        "--renew_billing_cost_transitions=\"0=JPY 101112\"",
        "--server_status_change_cost=\"JPY 97865\"",
        "--registry_lock_or_unlock_cost=\"JPY 9001\"",
        "xn--q9jyb4c");
    Registry registry = Registry.get("xn--q9jyb4c");
    assertThat(registry.getStandardCreateCost()).isEqualTo(Money.ofMajor(JPY, 12345));
    assertThat(registry.getStandardRestoreCost()).isEqualTo(Money.ofMajor(JPY, 67890));
    assertThat(registry.getStandardRenewCost(START_OF_TIME)).isEqualTo(Money.ofMajor(JPY, 101112));
    assertThat(registry.getServerStatusChangeCost()).isEqualTo(Money.ofMajor(JPY, 97865));
    assertThat(registry.getRegistryLockOrUnlockBillingCost()).isEqualTo(Money.ofMajor(JPY, 9001));
  }

  @Test
  void testSuccess_setLordnUsername() throws Exception {
    runCommandForced("--lordn_username=lordn000", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isEqualTo("lordn000");
  }

  @Test
  void testSuccess_setOptionalParamsNullString() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setLordnUsername("lordn000").build());
    runCommandForced("--lordn_username=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isNull();
  }

  @Test
  void testSuccess_setOptionalParamsEmptyString() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setLordnUsername("lordn000").build());
    runCommandForced("--lordn_username=", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isNull();
  }

  @Test
  void testSuccess_setReservedLists() throws Exception {
    runCommandForced("--reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");

    assertThat(Registry.get("xn--q9jyb4c").getReservedListNames())
        .containsExactly("xn--q9jyb4c_r1", "xn--q9jyb4c_r2");
  }

  @Test
  void testSuccess_setReservedListsOverwrites() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--reserved_lists=xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getReservedListNames())
        .containsExactly("xn--q9jyb4c_r2");
  }

  @Test
  void testSuccess_addReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1"))
        .build());
    runCommandForced("--add_reserved_lists=xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getReservedListNames())
        .containsExactly("xn--q9jyb4c_r1", "xn--q9jyb4c_r2");
  }

  @Test
  void testSuccess_removeAllReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--remove_reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getReservedListNames()).isEmpty();
  }

  @Test
  void testSuccess_removeSomeReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--remove_reserved_lists=xn--q9jyb4c_r1", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getReservedListNames())
        .containsExactly("xn--q9jyb4c_r2");
  }

  @Test
  void testSuccess_setAllowedRegistrants() throws Exception {
    runCommandForced("--allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  void testSuccess_setAllowedRegistrantsOverwrites() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("jane", "john"))
            .build());
    runCommandForced("--allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  void testSuccess_addAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice"))
            .build());
    runCommandForced("--add_allowed_registrants=bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  void testSuccess_removeAllAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
            .build());
    runCommandForced("--remove_allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds()).isEmpty();
  }

  @Test
  void testSuccess_removeSomeAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
            .build());
    runCommandForced("--remove_allowed_registrants=alice", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds()).containsExactly("bob");
  }

  @Test
  void testSuccess_setAllowedNameservers() throws Exception {
    runCommandForced("--allowed_nameservers=ns1.example.com,ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  void testSuccess_setAllowedNameserversOverwrites() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.tld", "ns2.example.tld"))
            .build());
    runCommandForced("--allowed_nameservers=ns1.example.com,ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  void testSuccess_addAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.com"))
            .build());
    runCommandForced("--add_allowed_nameservers=ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  void testSuccess_removeAllAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .build());
    runCommandForced("--remove_allowed_nameservers=ns1.example.com,ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames()).isEmpty();
  }

  @Test
  void testSuccess_removeSomeAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .build());
    runCommandForced("--remove_allowed_nameservers=ns1.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns2.example.com");
  }

  @Test
  void testFailure_invalidAddGracePeriod() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--add_grace_period=5m", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidRedemptionGracePeriod() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--redemption_grace_period=5m", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidPendingDeleteLength() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--pending_delete_length=5m", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidTldState() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--tld_state_transitions=" + START_OF_TIME + "=INVALID_STATE", "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("INVALID_STATE not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_invalidTldStateTransitionTime() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced("--tld_state_transitions=tomorrow=INVALID_STATE", "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("INVALID_STATE not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_tldStatesOutOfOrder() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=START_DATE_SUNRISE,%s=PREDELEGATION",
                        now, now.plusMonths(1)),
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("The TLD states are chronologically out of order");
  }

  @Test
  void testFailure_duplicateTldStateTransitions() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=START_DATE_SUNRISE,%s=START_DATE_SUNRISE",
                        now, now.plusMonths(1)),
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("The TLD states are chronologically out of order");
  }

  @Test
  void testFailure_duplicateTldStateTransitionTimes() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=PREDELEGATION,%s=START_DATE_SUNRISE", now, now),
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_outOfOrderTldStateTransitionTimes() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=PREDELEGATION,%s=START_DATE_SUNRISE",
                        now, now.minus(Duration.millis(1))),
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_bothTldStateFlags() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=PREDELEGATION,%s=START_DATE_SUNRISE",
                        now, now.plusDays(1)),
                    "--set_current_tld_state=GENERAL_AVAILABILITY",
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Don't pass both --set_current_tld_state and --tld_state_transitions");
    }

  @Test
  void testFailure_setCurrentTldState_outOfOrder() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, PREDELEGATION,
                    now.minusMonths(1), GENERAL_AVAILABILITY))
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--set_current_tld_state=START_DATE_SUNRISE", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("The TLD states are chronologically out of order");
  }

  @Test
  void testFailure_setCurrentTldState_laterTransitionScheduled() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, PREDELEGATION,
                    now.plusMonths(1), GENERAL_AVAILABILITY))
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--set_current_tld_state=START_DATE_SUNRISE", "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(" when there is a later transition already scheduled");
  }

  @Test
  void testFailure_setCurrentTldState_inProduction() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, PREDELEGATION,
                    now.minusMonths(1), GENERAL_AVAILABILITY))
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--set_current_tld_state=START_DATE_SUNRISE",
                    "xn--q9jyb4c",
                    "--force"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("--set_current_tld_state is not safe to use in production.");
  }

  @Test
  void testFailure_invalidRenewBillingCost() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    String.format("--renew_billing_cost_transitions=%s=US42", START_OF_TIME),
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_negativeRenewBillingCost() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format("--renew_billing_cost_transitions=%s=USD-42", START_OF_TIME),
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Renew billing cost cannot be negative");
  }

  @Test
  void testFailure_invalidRenewCostTransitionTime() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced("--renew_billing_cost_transitions=tomorrow=USD 1", "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_duplicateRenewCostTransitionTimes() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--renew_billing_cost_transitions=\"%s=USD 1,%s=USD 2\"", now, now),
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_outOfOrderRenewCostTransitionTimes() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--renew_billing_cost_transitions=\"%s=USD 1,%s=USD 2\"",
                        now, now.minus(Duration.millis(1))),
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("not formatted correctly or has transition times out of order");
  }

  @Test
  void testFailure_noTldName() {
    ParameterException thrown = assertThrows(ParameterException.class, this::runCommandForced);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Main parameters are required (\"Names of the TLDs\")");
  }

  @Test
  void testFailure_oneTldDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("foo", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("TLD foo does not exist");
  }

  @Test
  void testFailure_duplicateArguments() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("xn--q9jyb4c", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Duplicate arguments found: 'xn--q9jyb4c'");
  }

  @Test
  void testFailure_tldDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("foobarbaz"));
    assertThat(thrown).hasMessageThat().contains("TLD foobarbaz does not exist");
  }

  @Test
  void testFailure_specifiedDnsWriter_doesntExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("xn--q9jyb4c", "--dns_writers=InvalidDnsWriter"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Invalid DNS writer name(s) specified: [InvalidDnsWriter]");
  }

  @Test
  void testFailure_setNonExistentReservedLists() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--reserved_lists", "xn--q9jyb4c_ZZZ", "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not find reserved list xn--q9jyb4c_ZZZ to add to the tld");
  }

  @Test
  void testFailure_cantAddDuplicateReservedList() {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--add_reserved_lists=xn--q9jyb4c_r1", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("xn--q9jyb4c_r1");
  }

  @Test
  void testFailure_cantRemoveReservedListThatIsntPresent() {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--remove_reserved_lists=xn--q9jyb4c_Z", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("xn--q9jyb4c_Z");
  }

  @Test
  void testFailure_cantAddAndRemoveSameReservedListSimultaneously() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--add_reserved_lists=xn--q9jyb4c_r1",
                    "--remove_reserved_lists=xn--q9jyb4c_r1",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("xn--q9jyb4c_r1");
  }

  @Test
  void testFailure_cantAddDuplicateAllowedRegistrants() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
        .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--add_allowed_registrants=alice", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("alice");
  }

  @Test
  void testFailure_cantRemoveAllowedRegistrantThatIsntPresent() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("alice"))
        .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--remove_allowed_registrants=bob", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("bob");
  }

  @Test
  void testFailure_cantAddAndRemoveSameAllowedRegistrantsSimultaneously() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--add_allowed_registrants=alice",
                    "--remove_allowed_registrants=alice",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("alice");
  }

  @Test
  void testFailure_cantAddDuplicateAllowedNameservers() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--add_allowed_nameservers=ns1.example.com", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("ns1.example.com");
  }

  @Test
  void testFailure_cantRemoveAllowedNameserverThatIsntPresent() {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com"))
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--remove_allowed_nameservers=ns2.example.com", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("ns2.example.com");
  }

  @Test
  void testFailure_cantAddAndRemoveSameAllowedNameserversSimultaneously() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--add_allowed_nameservers=ns1.example.com",
                    "--remove_allowed_nameservers=ns1.example.com",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("ns1.example.com");
  }

  @Test
  void testSuccess_canSetRoidSuffixToWhatItAlreadyIs() throws Exception {
    createTld("foo", "BLAH");
    runCommandForced("--roid_suffix=BLAH", "foo");
    assertThat(Registry.get("foo").getRoidSuffix()).isEqualTo("BLAH");
  }

  @Test
  void testSuccess_updateRoidSuffix() throws Exception {
    createTld("foo", "ARGLE");
    runCommandForced("--roid_suffix=BARGLE", "foo");
    assertThat(Registry.get("foo").getRoidSuffix()).isEqualTo("BARGLE");
  }

  @Test
  void testSuccess_removePremiumListWithNull() throws Exception {
    runCommandForced("--premium_list=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumListName()).isEmpty();
  }

  @Test
  void testSuccess_removePremiumListWithBlank() throws Exception {
    runCommandForced("--premium_list=", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumListName()).isEmpty();
  }

  @Test
  void testSuccess_premiumListNotRemovedWhenNotSpecified() throws Exception {
    runCommandForced("--add_reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");
    Optional<String> premiumListName = Registry.get("xn--q9jyb4c").getPremiumListName();
    assertThat(premiumListName).hasValue("xn--q9jyb4c");
  }

  @Test
  void testSuccess_driveFolderId_notRemovedWhenNotSpecified() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setDriveFolderId("foobar").build());
    runCommandForced("xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isEqualTo("foobar");
  }

  @Test
  void testSuccess_setCommonReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse");
  }

  @Test
  void testSuccess_setTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("xn--q9jyb4c_abuse");
  }

  @Test
  void testSuccess_setCommonReservedListAndTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse,xn--q9jyb4c_abuse");
  }

  @Test
  void testFailure_setReservedListFromOtherTld() {
    runFailureReservedListsTest("tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setReservedListFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned");
  }

  @Test
  void testFailure_setCommonAndReservedListFromOtherTld() {
    runFailureReservedListsTest("common_abuse,tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setCommonAndReservedListFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("common_abuse,tld_banned");
    String errMsg =
        "Error overridden: The reserved list(s) tld_banned "
            + "cannot be applied to the tld xn--q9jyb4c";
    assertThat(getStderrAsString()).contains(errMsg);
  }

  @Test
  void testFailure_setMultipleReservedListsFromOtherTld() {
    runFailureReservedListsTest("tld_banned,soy_expurgated",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned, soy_expurgated cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setMultipleReservedListsFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned,soy_expurgated");
  }

  @Test
  void testSuccess_setPremiumList() throws Exception {
    runCommandForced("--premium_list=xn--q9jyb4c", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumListName()).hasValue("xn--q9jyb4c");
  }

  @Test
  void testSuccess_setDriveFolderIdToValue() throws Exception {
    runCommandForced("--drive_folder_id=madmax2030", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isEqualTo("madmax2030");
  }

  @Test
  void testSuccess_setDriveFolderIdToNull() throws Exception {
    runCommandForced("--drive_folder_id=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isNull();
  }

  @Test
  void testFailure_setPremiumListThatDoesntExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--premium_list=phonies", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("The premium list 'phonies' doesn't exist");
  }

  private void runSuccessfulReservedListsTest(String reservedLists) throws Exception {
    runCommandForced("--reserved_lists", reservedLists, "xn--q9jyb4c");
  }

  private void runReservedListsTestOverride(String reservedLists) throws Exception {
    runCommandForced("--override_reserved_list_rules",
        "--reserved_lists",
        reservedLists,
        "xn--q9jyb4c");
  }

  private void runFailureReservedListsTest(
      String reservedLists, Class<? extends Exception> errorClass, String errorMsg) {
    Exception e =
        assertThrows(
            errorClass, () -> runCommandForced("--reserved_lists", reservedLists, "xn--q9jyb4c"));
    assertThat(e).hasMessageThat().isEqualTo(errorMsg);
  }
}
