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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.AVAILABLE;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.BSA_BLOCKED;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.REGISTERED;
import static google.registry.monitoring.whitebox.CheckApiMetric.Availability.RESERVED;
import static google.registry.monitoring.whitebox.CheckApiMetric.Tier.PREMIUM;
import static google.registry.monitoring.whitebox.CheckApiMetric.Tier.STANDARD;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.Mockito.verify;

import google.registry.bsa.persistence.BsaTestingUtils;
import google.registry.model.tld.Tld;
import google.registry.monitoring.whitebox.CheckApiMetric;
import google.registry.monitoring.whitebox.CheckApiMetric.Availability;
import google.registry.monitoring.whitebox.CheckApiMetric.Status;
import google.registry.monitoring.whitebox.CheckApiMetric.Tier;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link CheckApiAction}. */
@ExtendWith(MockitoExtension.class)
class CheckApiActionTest {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");
  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @Mock private CheckApiMetrics checkApiMetrics;
  @Captor private ArgumentCaptor<CheckApiMetric> metricCaptor;

  private DateTime endTime;

  @BeforeEach
  void beforeEach() {
    createTld("example");
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "example-reserved",
                    "foo,FULLY_BLOCKED",
                    "gold,RESERVED_FOR_SPECIFIC_USE",
                    "platinum,FULLY_BLOCKED"))
            .build());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getCheckResponse(String domain) {
    CheckApiAction action = new CheckApiAction();
    action.domain = domain;
    action.response = new FakeResponse();
    action.metricBuilder = CheckApiMetric.builder(fakeClock);
    action.checkApiMetrics = checkApiMetrics;
    fakeClock.advanceOneMilli();
    endTime = fakeClock.nowUtc();

    action.run();
    return (Map<String, Object>) JSONValue.parse(((FakeResponse) action.response).getPayload());
  }

  @Test
  void testFailure_nullDomain() {
    assertThat(getCheckResponse(null))
        .containsExactly(
            "status", "error",
            "reason", "Must supply a valid domain name on an authoritative TLD");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_emptyDomain() {
    assertThat(getCheckResponse(""))
        .containsExactly(
            "status", "error",
            "reason", "Must supply a valid domain name on an authoritative TLD");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_invalidDomain() {
    assertThat(getCheckResponse("@#$%^"))
        .containsExactly(
            "status", "error",
            "reason", "Must supply a valid domain name on an authoritative TLD");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_singlePartDomain() {
    assertThat(getCheckResponse("foo"))
        .containsExactly(
            "status", "error",
            "reason", "Must supply a valid domain name on an authoritative TLD");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_nonExistentTld() {
    assertThat(getCheckResponse("foo.bar"))
        .containsExactly(
            "status", "error",
            "reason", "Must supply a valid domain name on an authoritative TLD");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_invalidIdnTable() {
    assertThat(getCheckResponse("ΑΒΓ.example"))
        .containsExactly(
            "status", "error",
            "reason", "Domain label is not allowed by IDN table");

    verifyFailureMetric(Status.INVALID_NAME);
  }

  @Test
  void testFailure_tldInPredelegation() {
    createTld("predelegated", PREDELEGATION);
    assertThat(getCheckResponse("foo.predelegated"))
        .containsExactly(
            "status", "error",
            "reason", "Check in this TLD is not allowed in the current registry phase");

    verifyFailureMetric(Status.INVALID_REGISTRY_PHASE);
  }

  @Test
  void testSuccess_availableStandard() {
    assertThat(getCheckResponse("somedomain.example"))
        .containsExactly(
            "status", "success",
            "available", true,
            "tier", "standard");

    verifySuccessMetric(STANDARD, AVAILABLE);
  }

  @Test
  void testSuccess_availableCapital() {
    assertThat(getCheckResponse("SOMEDOMAIN.EXAMPLE"))
        .containsExactly(
            "status", "success",
            "available", true,
            "tier", "standard");

    verifySuccessMetric(STANDARD, AVAILABLE);
  }

  @Test
  void testSuccess_availableUnicode() {
    assertThat(getCheckResponse("ééé.example"))
        .containsExactly(
            "status", "success",
            "available", true,
            "tier", "standard");

    verifySuccessMetric(STANDARD, AVAILABLE);
  }

  @Test
  void testSuccess_availablePunycode() {
    assertThat(getCheckResponse("xn--9caaa.example"))
        .containsExactly(
            "status", "success",
            "available", true,
            "tier", "standard");

    verifySuccessMetric(STANDARD, AVAILABLE);
  }

  @Test
  void testSuccess_availablePremium() {
    assertThat(getCheckResponse("rich.example"))
        .containsExactly(
            "status", "success",
            "available", true,
            "tier", "premium");

    verifySuccessMetric(PREMIUM, AVAILABLE);
  }

  @Test
  void testSuccess_registered_standard() {
    persistActiveDomain("somedomain.example");
    assertThat(getCheckResponse("somedomain.example"))
        .containsExactly(
            "tier", "standard",
            "status", "success",
            "available", false,
            "reason", "In use");

    verifySuccessMetric(STANDARD, REGISTERED);
  }

  @Test
  void testSuccess_reserved_standard() {
    assertThat(getCheckResponse("foo.example"))
        .containsExactly(
            "tier", "standard",
            "status", "success",
            "available", false,
            "reason", "Reserved");

    verifySuccessMetric(STANDARD, RESERVED);
  }

  @Test
  void testSuccess_registered_premium() {
    persistActiveDomain("rich.example");
    assertThat(getCheckResponse("rich.example"))
        .containsExactly(
            "tier", "premium",
            "status", "success",
            "available", false,
            "reason", "In use");

    verifySuccessMetric(PREMIUM, REGISTERED);
  }

  @Test
  void testSuccess_reserved_premium() {
    assertThat(getCheckResponse("platinum.example"))
        .containsExactly(
            "tier", "premium",
            "status", "success",
            "available", false,
            "reason", "Reserved");

    verifySuccessMetric(PREMIUM, RESERVED);
  }

  @Test
  void testSuccess_reservedForSpecificUse_premium() {
    assertThat(getCheckResponse("gold.example"))
        .containsExactly(
            "tier", "premium",
            "status", "success",
            "available", false,
            "reason", "Reserved; alloc. token required");

    verifySuccessMetric(PREMIUM, RESERVED);
  }

  @Test
  void testSuccess_blockedByBsa() {
    BsaTestingUtils.persistBsaLabel("rich");
    persistResource(
        Tld.get("example").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    assertThat(getCheckResponse("rich.example"))
        .containsExactly(
            "tier", "premium",
            "status", "success",
            "available", false,
            "reason", "Blocked by a GlobalBlock service");

    verifySuccessMetric(PREMIUM, BSA_BLOCKED);
  }

  private void verifySuccessMetric(Tier tier, Availability availability) {
    verify(checkApiMetrics).incrementCheckApiRequest(metricCaptor.capture());
    CheckApiMetric metric = metricCaptor.getValue();

    verify(checkApiMetrics).recordProcessingTime(metric);
    assertThat(metric.availability()).hasValue(availability);
    assertThat(metric.tier()).hasValue(tier);
    assertThat(metric.status()).isEqualTo(Status.SUCCESS);
    assertThat(metric.startTimestamp()).isEqualTo(START_TIME);
    assertThat(metric.endTimestamp()).isEqualTo(endTime);
  }

  private void verifyFailureMetric(Status status) {
    verify(checkApiMetrics).incrementCheckApiRequest(metricCaptor.capture());
    CheckApiMetric metric = metricCaptor.getValue();

    verify(checkApiMetrics).recordProcessingTime(metric);
    assertThat(metric.availability()).isEmpty();
    assertThat(metric.tier()).isEmpty();
    assertThat(metric.status()).isEqualTo(status);
    assertThat(metric.startTimestamp()).isEqualTo(START_TIME);
    assertThat(metric.endTimestamp()).isEqualTo(endTime);
  }
}
