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

import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.label.ReservedListTest.GET_NAME_FUNCTION;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.BasePremiumList;
import google.registry.model.registry.label.PremiumList;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link UpdateTldCommand}. */
public class UpdateTldCommandTest extends CommandTestCase<UpdateTldCommand> {

  private final DateTime now = DateTime.now(UTC);

  @Before
  public void initTest() {
    persistReservedList("common_abuse", "baa,FULLY_BLOCKED");
    persistReservedList("xn--q9jyb4c_abuse", "lamb,FULLY_BLOCKED");
    persistReservedList("tld_banned", "kilo,FULLY_BLOCKED", "lima,MISTAKEN_PREMIUM");
    persistReservedList("soy_expurgated", "fireflies,FULLY_BLOCKED");
    persistPremiumList("xn--q9jyb4c", "minecraft,USD 1000");
    persistReservedList("xn--q9jyb4c_r1", "foo,FULLY_BLOCKED");
    persistReservedList("xn--q9jyb4c_r2", "moop,FULLY_BLOCKED");
    createTld("xn--q9jyb4c");
  }

  @Test
  public void testSuccess_tldStateTransitions() throws Exception {
    DateTime sunriseStart = now;
    DateTime sunrushStart = sunriseStart.plusMonths(2);
    DateTime quietPeriodStart = sunrushStart.plusMonths(1);
    DateTime gaStart = quietPeriodStart.plusWeeks(1);
    runCommandForced(
        String.format(
            "--tld_state_transitions=%s=PREDELEGATION,%s=SUNRISE,%s=SUNRUSH,%s=QUIET_PERIOD,"
                + "%s=GENERAL_AVAILABILITY",
            START_OF_TIME,
            sunriseStart,
            sunrushStart,
            quietPeriodStart,
            gaStart),
        "xn--q9jyb4c");

    Registry registry = Registry.get("xn--q9jyb4c");
    assertThat(registry.getTldState(sunriseStart.minusMillis(1))).isEqualTo(TldState.PREDELEGATION);
    assertThat(registry.getTldState(sunriseStart)).isEqualTo(TldState.SUNRISE);
    assertThat(registry.getTldState(sunriseStart.plusMillis(1))).isEqualTo(TldState.SUNRISE);
    assertThat(registry.getTldState(sunrushStart.minusMillis(1))).isEqualTo(TldState.SUNRISE);
    assertThat(registry.getTldState(sunrushStart)).isEqualTo(TldState.SUNRUSH);
    assertThat(registry.getTldState(sunrushStart.plusMillis(1))).isEqualTo(TldState.SUNRUSH);
    assertThat(registry.getTldState(quietPeriodStart.minusMillis(1))).isEqualTo(TldState.SUNRUSH);
    assertThat(registry.getTldState(quietPeriodStart)).isEqualTo(TldState.QUIET_PERIOD);
    assertThat(registry.getTldState(quietPeriodStart.plusMillis(1)))
        .isEqualTo(TldState.QUIET_PERIOD);
    assertThat(registry.getTldState(gaStart.minusMillis(1))).isEqualTo(TldState.QUIET_PERIOD);
    assertThat(registry.getTldState(gaStart)).isEqualTo(TldState.GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(gaStart.plusMillis(1)))
        .isEqualTo(TldState.GENERAL_AVAILABILITY);
    assertThat(registry.getTldState(END_OF_TIME)).isEqualTo(TldState.GENERAL_AVAILABILITY);
  }

  @Test
  public void testSuccess_setTldState() throws Exception {
    Registry registry = persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, TldState.PREDELEGATION))
            .build());
    runCommandForced("--set_current_tld_state=SUNRISE", "xn--q9jyb4c");
    registry = Registry.get("xn--q9jyb4c");
    assertThat(registry.getTldState(now.plusDays(1))).isEqualTo(TldState.SUNRISE);
  }

  @Test
  public void testSuccess_renewBillingCostTransitions() throws Exception {
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
  public void testSuccess_multipleArguments() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));
    createTld("example");
    assertThat(Registry.get("example").getAddGracePeriodLength()).isNotEqualTo(standardMinutes(5));

    runCommandForced("--add_grace_period=PT300S", "xn--q9jyb4c", "example");

    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
    assertThat(Registry.get("example").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  public void testSuccess_addGracePeriodFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));

    runCommandForced("--add_grace_period=PT300S", "xn--q9jyb4c");

    assertThat(Registry.get("xn--q9jyb4c").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  public void testSuccess_redemptionGracePeriodFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getRedemptionGracePeriodLength())
        .isNotEqualTo(standardMinutes(5));

    runCommandForced("--redemption_grace_period=PT300S", "xn--q9jyb4c");

    assertThat(Registry.get("xn--q9jyb4c").getRedemptionGracePeriodLength())
        .isEqualTo(standardMinutes(5));
  }

  @Test
  public void testSuccess_pendingDeleteLengthFlag() throws Exception {
    assertThat(Registry.get("xn--q9jyb4c").getPendingDeleteLength())
        .isNotEqualTo(standardMinutes(5));

    runCommandForced("--pending_delete_length=PT300S", "xn--q9jyb4c");

    assertThat(Registry.get("xn--q9jyb4c").getPendingDeleteLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  public void testSuccess_escrow() throws Exception {
    runCommandForced("--escrow=true", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getEscrowEnabled()).isTrue();
  }

  @Test
  public void testSuccess_noEscrow() throws Exception {
    runCommandForced("--escrow=false", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getEscrowEnabled()).isFalse();
  }

  @Test
  public void testSuccess_createBillingCostFlag() throws Exception {
    runCommandForced("--create_billing_cost=\"USD 42.42\"", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getStandardCreateCost()).isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  public void testSuccess_restoreBillingCostFlag() throws Exception {
    runCommandForced("--restore_billing_cost=\"USD 42.42\"", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getStandardRestoreCost())
        .isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  public void testSuccess_nonUsdBillingCostFlag() throws Exception {
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
            .build());
    runCommandForced(
        "--create_billing_cost=\"JPY 12345\"",
        "--restore_billing_cost=\"JPY 67890\"",
        "--renew_billing_cost_transitions=\"0=JPY 101112\"",
        "--server_status_change_cost=\"JPY 97865\"",
        "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getStandardCreateCost())
        .isEqualTo(Money.ofMajor(JPY, 12345));
    assertThat(Registry.get("xn--q9jyb4c").getStandardRestoreCost())
        .isEqualTo(Money.ofMajor(JPY, 67890));
    assertThat(Registry.get("xn--q9jyb4c").getStandardRenewCost(START_OF_TIME))
        .isEqualTo(Money.ofMajor(JPY, 101112));
  }

  @Test
  public void testSuccess_setPremiumPriceAckRequired() throws Exception {
    runCommandForced("--premium_price_ack_required=true", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumPriceAckRequired()).isTrue();
  }

  @Test
  public void testSuccess_clearPremiumPriceAckRequired() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setPremiumPriceAckRequired(true).build());
    runCommandForced("--premium_price_ack_required=false", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumPriceAckRequired()).isFalse();
  }

  @Test
  public void testSuccess_setLordnUsername() throws Exception {
    runCommandForced("--lordn_username=lordn000", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isEqualTo("lordn000");
  }

  @Test
  public void testSuccess_setOptionalParamsNullString() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setLordnUsername("lordn000").build());
    runCommandForced("--lordn_username=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isNull();
  }

  @Test
  public void testSuccess_setOptionalParamsEmptyString() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setLordnUsername("lordn000").build());
    runCommandForced("--lordn_username=", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLordnUsername()).isNull();
  }

  @Test
  public void testSuccess_setReservedLists() throws Exception {
    runCommandForced("--reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");

    assertThat(transform(Registry.get("xn--q9jyb4c").getReservedLists(), GET_NAME_FUNCTION))
        .containsExactly("xn--q9jyb4c_r1", "xn--q9jyb4c_r2");
  }

  @Test
  public void testSuccess_setReservedListsOverwrites() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--reserved_lists=xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(transform(Registry.get("xn--q9jyb4c").getReservedLists(), GET_NAME_FUNCTION))
        .containsExactly("xn--q9jyb4c_r2");
  }

  @Test
  public void testSuccess_addReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1"))
        .build());
    runCommandForced("--add_reserved_lists=xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(transform(Registry.get("xn--q9jyb4c").getReservedLists(), GET_NAME_FUNCTION))
        .containsExactly("xn--q9jyb4c_r1", "xn--q9jyb4c_r2");
  }

  @Test
  public void testSuccess_removeAllReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--remove_reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getReservedLists()).isEmpty();
  }

  @Test
  public void testSuccess_removeSomeReservedLists() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    runCommandForced("--remove_reserved_lists=xn--q9jyb4c_r1", "xn--q9jyb4c");
    assertThat(transform(Registry.get("xn--q9jyb4c").getReservedLists(), GET_NAME_FUNCTION))
        .containsExactly("xn--q9jyb4c_r2");
  }

  @Test
  public void testSuccess_setAllowedRegistrants() throws Exception {
    runCommandForced("--allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  public void testSuccess_setAllowedRegistrantsOverwrites() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("jane", "john"))
            .build());
    runCommandForced("--allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  public void testSuccess_addAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice"))
            .build());
    runCommandForced("--add_allowed_registrants=bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  public void testSuccess_removeAllAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
            .build());
    runCommandForced("--remove_allowed_registrants=alice,bob", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds()).isEmpty();
  }

  @Test
  public void testSuccess_removeSomeAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
            .build());
    runCommandForced("--remove_allowed_registrants=alice", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedRegistrantContactIds()).containsExactly("bob");
  }

  @Test
  public void testSuccess_setAllowedNameservers() throws Exception {
    runCommandForced("--allowed_nameservers=ns1.example.com,ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  public void testSuccess_setAllowedNameserversOverwrites() throws Exception {
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
  public void testSuccess_addAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.com"))
            .build());
    runCommandForced("--add_allowed_nameservers=ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  public void testSuccess_removeAllAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .build());
    runCommandForced("--remove_allowed_nameservers=ns1.example.com,ns2.example.com", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames()).isEmpty();
  }

  @Test
  public void testSuccess_removeSomeAllowedNameservers() throws Exception {
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
  public void testSuccess_removeLrpPeriod() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setLrpPeriod(new Interval(
                DateTime.parse("2004-06-09T12:30:00Z"), DateTime.parse("2004-07-10T13:30:00Z")))
            .build());
    runCommandForced("--lrp_period=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLrpPeriod())
        .isEqualTo(new Interval(START_OF_TIME, Duration.ZERO));
  }

  @Test
  public void testFailure_invalidAddGracePeriod() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("--add_grace_period=5m", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_invalidRedemptionGracePeriod() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("--redemption_grace_period=5m", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_invalidPendingDeleteLength() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("--pending_delete_length=5m", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_invalidTldState() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced("--tld_state_transitions=" + START_OF_TIME + "=INVALID_STATE", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_invalidTldStateTransitionTime() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced("--tld_state_transitions=tomorrow=INVALID_STATE", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_tldStatesOutOfOrder() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced(
        String.format(
            "--tld_state_transitions=%s=SUNRISE,%s=PREDELEGATION", now, now.plusMonths(1)),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_duplicateTldStateTransitions() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced(
        String.format("--tld_state_transitions=%s=SUNRISE,%s=SUNRISE", now, now.plusMonths(1)),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_duplicateTldStateTransitionTimes() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        String.format("--tld_state_transitions=%s=PREDELEGATION,%s=SUNRISE", now, now),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_outOfOrderTldStateTransitionTimes() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        String.format("--tld_state_transitions=%s=PREDELEGATION,%s=SUNRISE", now, now.minus(1)),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_bothTldStateFlags() throws Exception {
    thrown.expect(
        IllegalArgumentException.class,
        "Don't pass both --set_current_tld_state and --tld_state_transitions");
    runCommandForced(
        String.format("--tld_state_transitions=%s=PREDELEGATION,%s=SUNRISE", now, now.plusDays(1)),
        "--set_current_tld_state=GENERAL_AVAILABILITY",
        "xn--q9jyb4c");
    }

  @Test
  public void testFailure_setCurrentTldState_outOfOrder() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, TldState.PREDELEGATION,
                    now.minusMonths(1), TldState.GENERAL_AVAILABILITY))
            .build());
    thrown.expect(
        IllegalArgumentException.class, "The TLD states are chronologically out of order");
    runCommandForced("--set_current_tld_state=SUNRISE", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_setCurrentTldState_laterTransitionScheduled() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, TldState.PREDELEGATION,
                    now.plusMonths(1), TldState.GENERAL_AVAILABILITY))
            .build());
    thrown.expect(
        IllegalArgumentException.class,
        " when there is a later transition already scheduled");
    runCommandForced("--set_current_tld_state=SUNRISE", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_setCurrentTldState_inProduction() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, TldState.PREDELEGATION,
                    now.minusMonths(1), TldState.GENERAL_AVAILABILITY))
            .build());
    thrown.expect(
        IllegalArgumentException.class,
        "--set_current_tld_state is not safe to use in production.");
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--set_current_tld_state=SUNRISE",
        "xn--q9jyb4c",
        "--force");
  }

  @Test
  public void testFailure_invalidRenewBillingCost() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        String.format("--renew_billing_cost_transitions=%s=US42", START_OF_TIME),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_negativeRenewBillingCost() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced(
        String.format("--renew_billing_cost_transitions=%s=USD-42", START_OF_TIME),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_invalidRenewCostTransitionTime() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced("--renew_billing_cost_transitions=tomorrow=USD 1", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_duplicateRenewCostTransitionTimes() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        String.format("--renew_billing_cost_transitions=\"%s=USD 1,%s=USD 2\"", now, now),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_outOfOrderRenewCostTransitionTimes() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        String.format("--renew_billing_cost_transitions=\"%s=USD 1,%s=USD 2\"", now, now.minus(1)),
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_noTldName() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced();
  }

  @Test
  public void testFailure_oneArgumentDoesNotExist() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("foo", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_duplicateArguments() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("xn--q9jyb4c", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_tldDoesNotExist() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced("foobarbaz");
  }

  @Test
  public void testFailure_setNonExistentReservedLists() throws Exception {
    thrown.expect(
        IllegalStateException.class,
        "Could not find reserved list xn--q9jyb4c_ZZZ to add to the tld");
    runCommandForced("--reserved_lists", "xn--q9jyb4c_ZZZ", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddDuplicateReservedList() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    thrown.expect(IllegalArgumentException.class, "xn--q9jyb4c_r1");
    runCommandForced("--add_reserved_lists=xn--q9jyb4c_r1", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantRemoveReservedListThatIsntPresent() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder()
        .setReservedListsByName(ImmutableSet.of("xn--q9jyb4c_r1", "xn--q9jyb4c_r2"))
        .build());
    thrown.expect(IllegalArgumentException.class, "xn--q9jyb4c_Z");
    runCommandForced("--remove_reserved_lists=xn--q9jyb4c_Z", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddAndRemoveSameReservedListSimultaneously() throws Exception {
    thrown.expect(IllegalArgumentException.class, "xn--q9jyb4c_r1");
    runCommandForced(
        "--add_reserved_lists=xn--q9jyb4c_r1",
        "--remove_reserved_lists=xn--q9jyb4c_r1",
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddDuplicateAllowedRegistrants() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("alice", "bob"))
        .build());
    thrown.expect(IllegalArgumentException.class, "alice");
    runCommandForced("--add_allowed_registrants=alice", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantRemoveAllowedRegistrantThatIsntPresent() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("alice"))
        .build());
    thrown.expect(IllegalArgumentException.class, "bob");
    runCommandForced("--remove_allowed_registrants=bob", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddAndRemoveSameAllowedRegistrantsSimultaneously() throws Exception {
    thrown.expect(IllegalArgumentException.class, "alice");
    runCommandForced(
        "--add_allowed_registrants=alice",
        "--remove_allowed_registrants=alice",
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddDuplicateAllowedNameservers() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com", "ns2.example.com"))
            .build());
    thrown.expect(IllegalArgumentException.class, "ns1.example.com");
    runCommandForced("--add_allowed_nameservers=ns1.example.com", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantRemoveAllowedNameserverThatIsntPresent() throws Exception {
    persistResource(
        Registry.get("xn--q9jyb4c").asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.com"))
            .build());
    thrown.expect(IllegalArgumentException.class, "ns2.example.com");
    runCommandForced("--remove_allowed_nameservers=ns2.example.com", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_cantAddAndRemoveSameAllowedNameserversSimultaneously() throws Exception {
    thrown.expect(IllegalArgumentException.class, "ns1.example.com");
    runCommandForced(
        "--add_allowed_nameservers=ns1.example.com",
        "--remove_allowed_nameservers=ns1.example.com",
        "xn--q9jyb4c");
  }

  @Test
  public void testFailure_roidSuffixAlreadyInUse() throws Exception {
    createTld("foo", "BLAH");
    createTld("bar", "BAR");
    thrown.expect(IllegalArgumentException.class, "The roid suffix BLAH is already in use");
    runCommandForced("--roid_suffix=BLAH", "bar");
  }

  @Test
  public void testSuccess_canSetRoidSuffixToWhatItAlreadyIs() throws Exception {
    createTld("foo", "BLAH");
    runCommandForced("--roid_suffix=BLAH", "foo");
    assertThat(Registry.get("foo").getRoidSuffix()).isEqualTo("BLAH");
  }

  @Test
  public void testSuccess_updateRoidSuffix() throws Exception {
    createTld("foo", "ARGLE");
    runCommandForced("--roid_suffix=BARGLE", "foo");
    assertThat(Registry.get("foo").getRoidSuffix()).isEqualTo("BARGLE");
  }

  @Test
  public void testSuccess_removePremiumListWithNull() throws Exception {
    runCommandForced("--premium_list=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumList()).isNull();
  }

  @Test
  public void testSuccess_removePremiumListWithBlank() throws Exception {
    runCommandForced("--premium_list=", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumList()).isNull();
  }

  @Test
  public void testSuccess_premiumListNotRemovedWhenNotSpecified() throws Exception {
    runCommandForced("--add_reserved_lists=xn--q9jyb4c_r1,xn--q9jyb4c_r2", "xn--q9jyb4c");
    Key<BasePremiumList> premiumListKey = Registry.get("xn--q9jyb4c").getPremiumList();
    assertThat(premiumListKey).isNotNull();
    assertThat(premiumListKey.getName()).isEqualTo("xn--q9jyb4c");
  }

  @Test
  public void testSuccess_driveFolderId_notRemovedWhenNotSpecified() throws Exception {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setDriveFolderId("foobar").build());
    runCommandForced("xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isEqualTo("foobar");
  }

  @Test
  public void testSuccess_setCommonReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse");
  }

  @Test
  public void testSuccess_setTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("xn--q9jyb4c_abuse");
  }

  @Test
  public void testSuccess_setCommonReservedListAndTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse,xn--q9jyb4c_abuse");
  }

  @Test
  public void testFailure_setReservedListFromOtherTld() throws Exception {
    runFailureReservedListsTest("tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  public void testSuccess_setReservedListFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned");
  }

  @Test
  public void testFailure_setCommonAndReservedListFromOtherTld() throws Exception {
    runFailureReservedListsTest("common_abuse,tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  public void testSuccess_setCommonAndReservedListFromOtherTld_withOverride() throws Exception {
    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errContent));
    runReservedListsTestOverride("common_abuse,tld_banned");
    String errMsg =
        "Error overriden: The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c";
    assertThat(errContent.toString()).contains(errMsg);
    System.setOut(null);
  }

  @Test
  public void testFailure_setMultipleReservedListsFromOtherTld() throws Exception {
    runFailureReservedListsTest("tld_banned,soy_expurgated",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned, soy_expurgated cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  public void testSuccess_setMultipleReservedListsFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned,soy_expurgated");
  }

  @Test
  public void testSuccess_setPremiumList() throws Exception {
    runCommandForced("--premium_list=xn--q9jyb4c", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getPremiumList().getName()).isEqualTo("xn--q9jyb4c");
  }

  @Test
  public void testSuccess_setDriveFolderIdToValue() throws Exception {
    runCommandForced("--drive_folder_id=madmax2030", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isEqualTo("madmax2030");
  }

  @Test
  public void testSuccess_setDriveFolderIdToNull() throws Exception {
    runCommandForced("--drive_folder_id=null", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getDriveFolderId()).isNull();
  }

  @Test
  public void testFailure_setPremiumListThatDoesntExist() throws Exception {
    thrown.expect(IllegalArgumentException.class, "The premium list 'phonies' doesn't exist");
    runCommandForced("--premium_list=phonies", "xn--q9jyb4c");
  }

  @Test
  public void testSuccess_updateLrpPeriod() throws Exception {
    runCommandForced("--lrp_period=2004-06-09T12:30:00Z/2004-07-10T13:30:00Z", "xn--q9jyb4c");
    assertThat(Registry.get("xn--q9jyb4c").getLrpPeriod()).isEqualTo(
        new Interval(
            DateTime.parse("2004-06-09T12:30:00Z"), DateTime.parse("2004-07-10T13:30:00Z")));
  }

  @Test
  public void testFailure_updateLrpPeriod_backwardsInterval() throws Exception {
    thrown.expect(
        ParameterException.class,
        "--lrp_period=2005-06-09T12:30:00Z/2004-07-10T13:30:00Z not an ISO-8601 interval");
    runCommandForced("--lrp_period=2005-06-09T12:30:00Z/2004-07-10T13:30:00Z", "xn--q9jyb4c");
  }

  @Test
  public void testFailure_updateLrpPeriod_badInterval() throws Exception {
    thrown.expect(ParameterException.class, "--lrp_period=foobar not an ISO-8601 interval");
    runCommandForced("--lrp_period=foobar", "xn--q9jyb4c");
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
      String reservedLists,
      Class<? extends Exception> errorClass,
      String errorMsg) throws Exception {
    thrown.expect(errorClass, errorMsg);
    runCommandForced("--reserved_lists", reservedLists, "xn--q9jyb4c");
  }
}
