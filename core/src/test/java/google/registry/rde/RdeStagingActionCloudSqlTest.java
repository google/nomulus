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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.BeamActionTestBase;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Registry;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestSqlOnly;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdeStagingAction} in Cloud SQL. */
@DualDatabaseTest
public class RdeStagingActionCloudSqlTest extends BeamActionTestBase {

  private final FakeClock clock = new FakeClock();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final RdeStagingAction action = new RdeStagingAction();

  @RegisterExtension
  public final AppEngineExtension extension =
      AppEngineExtension.builder().withClock(clock).withDatastoreAndCloudSql().build();

  @BeforeEach
  @Override
  public void beforeEach() throws Exception {
    super.beforeEach();
    action.clock = clock;
    action.lenient = false;
    action.projectId = "projectId";
    action.jobRegion = "jobRegion";
    action.rdeBucket = "rde-bucket";
    action.pendingDepositChecker = new PendingDepositChecker();
    action.pendingDepositChecker.brdaDayOfWeek = DateTimeConstants.TUESDAY;
    action.pendingDepositChecker.brdaInterval = Duration.standardDays(7);
    action.pendingDepositChecker.clock = clock;
    action.pendingDepositChecker.rdeInterval = Duration.standardDays(1);
    action.gcsUtils = gcsUtils;
    action.response = response;
    action.transactionCooldown = Duration.ZERO;
    action.stagingKeyBytes = "ABCE".getBytes(StandardCharsets.UTF_8);
    action.directory = Optional.empty();
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of();
    action.revision = Optional.empty();
    action.dataflow = dataflow;
    action.machineType = "machine-type";
  }

  @TestSqlOnly
  void testRun_modeInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.modeStrings = ImmutableSet.of("full");
    assertThrows(BadRequestException.class, action::run);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_tldInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.tlds = ImmutableSet.of("tld");
    assertThrows(BadRequestException.class, action::run);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_watermarkInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_revisionInNonManualMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.revision = Optional.of(42);
    assertThrows(BadRequestException.class, action::run);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_noTlds_returns204() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_tldWithoutEscrowEnabled_returns204() {
    createTld("lol");
    persistResource(Registry.get("lol").asBuilder().setEscrowEnabled(false).build());
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_tldWithEscrowEnabled_launchesPipeline() throws Exception {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Launched RDE pipeline: jobid");
    verify(templates, times(1))
        .launch(eq("projectId"), eq("jobRegion"), any(LaunchFlexTemplateRequest.class));
  }

  @TestSqlOnly
  void testRun_withinTransactionCooldown_getsExcludedAndReturns204() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:04:59Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertThat(response.getStatus()).isEqualTo(204);
    verifyNoMoreInteractions(dataflow);
  }

  @TestSqlOnly
  void testRun_afterTransactionCooldown_runsPipeline() throws Exception {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01T00:05:00Z"));
    action.transactionCooldown = Duration.standardMinutes(5);
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Launched RDE pipeline: jobid");
    verify(templates, times(1))
        .launch(eq("projectId"), eq("jobRegion"), any(LaunchFlexTemplateRequest.class));
  }

  @TestSqlOnly
  void testManualRun_emptyMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of();
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @TestSqlOnly
  void testManualRun_invalidMode_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full", "thing");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @TestSqlOnly
  void testManualRun_emptyTld_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of();
    action.watermarks = ImmutableSet.of(clock.nowUtc());
    assertThrows(BadRequestException.class, action::run);
  }

  @TestSqlOnly
  void testManualRun_emptyWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of();
    assertThrows(BadRequestException.class, action::run);
  }

  @TestSqlOnly
  void testManualRun_nonDayStartWatermark_throwsException() {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks = ImmutableSet.of(DateTime.parse("2001-01-01T01:36:45Z"));
    assertThrows(BadRequestException.class, action::run);
  }

  @TestSqlOnly
  void testManualRun_invalidRevision_throwsException() {
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

  @TestSqlOnly
  void testManualRun_validParameters_runsPipeline() throws Exception {
    createTldWithEscrowEnabled("lol");
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    action.manual = true;
    action.directory = Optional.of("test/");
    action.modeStrings = ImmutableSet.of("full");
    action.tlds = ImmutableSet.of("lol");
    action.watermarks =
        ImmutableSet.of(DateTime.parse("1999-12-31TZ"), DateTime.parse("2001-01-01TZ"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Launched RDE pipeline: jobid, jobid1");
    verify(templates, times(2))
        .launch(eq("projectId"), eq("jobRegion"), any(LaunchFlexTemplateRequest.class));
  }

  private static void createTldWithEscrowEnabled(final String tld) {
    createTld(tld);
    persistResource(Registry.get(tld).asBuilder().setEscrowEnabled(true).build());
  }
}
