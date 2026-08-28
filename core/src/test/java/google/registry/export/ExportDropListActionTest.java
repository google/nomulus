// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.MediaType;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.ExpiryAccessPeriodMode;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ExportDropListAction}. */
class ExportDropListActionTest {

  private final DriveConnection driveConnection = mock(DriveConnection.class);
  private final ArgumentCaptor<byte[]> bytesExportedToDrive = ArgumentCaptor.forClass(byte[].class);
  private ExportDropListAction action;
  private final FakeClock clock = new FakeClock(Instant.parse("2020-02-02T02:02:02Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("open1");
    persistResource(
        Tld.get("open1")
            .asBuilder()
            .setInvoicingEnabled(true)
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());

    createTld("open2");
    persistResource(
        Tld.get("open2")
            .asBuilder()
            .setInvoicingEnabled(true)
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());

    createTld("closed");
    persistResource(Tld.get("closed").asBuilder().setInvoicingEnabled(false).build());

    createTld("testtld");
    persistResource(
        Tld.get("testtld").asBuilder().setTldType(TldType.TEST).setInvoicingEnabled(true).build());

    action = new ExportDropListAction();
    action.clock = clock;
    action.driveConnection = driveConnection;
    action.driveFolderId = Optional.of("drop_list_folder_id");
  }

  private void verifyExportedToDrive(String expectedCsv) throws Exception {
    verify(driveConnection)
        .createOrUpdateFile(
            eq("domain_drop_list.csv"),
            eq(MediaType.CSV_UTF_8),
            eq("drop_list_folder_id"),
            bytesExportedToDrive.capture());
    assertThat(new String(bytesExportedToDrive.getValue(), UTF_8)).isEqualTo(expectedCsv);
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  void test_exportsDropListAcrossOpenTlds_sortedByDomainName() throws Exception {
    // Active domain with no drop date (END_INSTANT) on open TLD -> excluded
    persistActiveDomain("active.open1");

    // Pending delete domains on open TLDs -> included
    persistDeletedDomain("zebra.open1", Instant.parse("2020-02-07T02:02:02Z"));
    persistDeletedDomain("alpha.open2", Instant.parse("2020-02-04T02:02:02Z"));

    // Pending delete domain on non-invoicing (closed) TLD -> excluded
    persistDeletedDomain("closed.closed", Instant.parse("2020-02-05T02:02:02Z"));

    // Pending delete domain on open TLD with XAP disabled -> excluded
    createTld("noxap");
    persistResource(
        Tld.get("noxap")
            .asBuilder()
            .setInvoicingEnabled(true)
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.DISABLED))
            .build());
    persistDeletedDomain("noxap.noxap", Instant.parse("2020-02-05T02:02:02Z"));

    // Pending delete domain on test TLD -> excluded
    persistDeletedDomain("test.testtld", Instant.parse("2020-02-06T02:02:02Z"));

    // Already deleted domain on open TLD -> excluded
    persistDeletedDomain("deleted.open1", Instant.parse("2020-02-01T02:02:02Z"));

    action.run();

    verifyExportedToDrive(
        """
        domain_name,tld,deletion_time
        alpha.open2,open2,2020-02-04T02:02:02Z
        zebra.open1,open1,2020-02-07T02:02:02Z
        """);
  }

  @Test
  void test_skipsDriveExport_whenDriveFolderIdIsEmpty() {
    action.driveFolderId = Optional.empty();
    action.run();
    verifyNoInteractions(driveConnection);
  }

  @Test
  void test_skipsDriveExport_whenDriveFolderIdIsEmptyString() {
    action.driveFolderId = Optional.of("");
    action.run();
    verifyNoInteractions(driveConnection);
  }

  @Test
  void test_emptyDropList_outputsHeaderOnly() throws Exception {
    persistActiveDomain("active.open1");
    action.run();
    verifyExportedToDrive("domain_name,tld,deletion_time\n");
  }

  @Test
  void test_noOpenTldsWithXap_outputsHeaderOnly() throws Exception {
    persistResource(
        Tld.get("open1")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.DISABLED))
            .build());
    persistResource(
        Tld.get("open2")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.DISABLED))
            .build());

    persistDeletedDomain("zebra.open1", Instant.parse("2020-02-07T02:02:02Z"));

    action.run();
    verifyExportedToDrive("domain_name,tld,deletion_time\n");
  }

  @Test
  void test_rethrowsRuntimeException_whenDriveFails() throws Exception {
    persistDeletedDomain("alpha.open2", Instant.parse("2020-02-04T02:02:02Z"));
    when(driveConnection.createOrUpdateFile(any(), any(), any(), any()))
        .thenThrow(new IOException("Drive timeout"));

    RuntimeException thrown = assertThrows(RuntimeException.class, () -> action.run());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Error exporting domain drop list to Drive folder drop_list_folder_id");
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("Drive timeout");
  }
}
