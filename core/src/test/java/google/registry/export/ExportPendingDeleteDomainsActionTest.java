// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.export.ExportPendingDeleteDomainsAction.PENDING_DELETE_DOMAINS_FILENAME;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Tests for {@link ExportPendingDeleteDomainsAction}. */
public class ExportPendingDeleteDomainsActionTest {

  private final DriveConnection driveConnection = mock(DriveConnection.class);
  private final ArgumentCaptor<byte[]> bytesExportedToDrive = ArgumentCaptor.forClass(byte[].class);
  private ExportPendingDeleteDomainsAction action;

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-05-13T13:13:13Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    createTld("testtld");
    persistResource(Tld.get("tld").asBuilder().setDriveFolderId("brouhaha").build());
    persistResource(Tld.get("testtld").asBuilder().setTldType(Tld.TldType.TEST).build());
    action = new ExportPendingDeleteDomainsAction(driveConnection);
  }

  @Test
  void testPendingDeleteDomainsInRealTlds() throws Exception {
    persistActiveDomain("active.tld");
    persistDomainWithPendingDelete("pendingdelete.tld");
    clock.advanceOneMilli();
    persistDomainWithPendingDelete("pendingdelete2.tld");
    persistDomainWithPendingDelete("pendingdelete.testtld");

    action.run();

    verifyExportedToDrive(
        "brouhaha",
        "pendingdelete.tld,2024-06-07T13:13:13.000Z\npendingdelete2.tld,2024-06-07T13:13:13.001Z");
  }

  @Test
  void testNoDriveFolder_noExport() throws Exception {
    persistDomainWithPendingDelete("pendingdelete.tld");
    persistResource(Tld.get("tld").asBuilder().setDriveFolderId(null).build());
    action.run();
    verifyNoMoreInteractions(driveConnection);
  }

  private void persistDomainWithPendingDelete(String domainName) {
    persistResource(
        persistActiveDomain(domainName)
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
  }

  private void verifyExportedToDrive(String folderId, String content) throws Exception {
    verify(driveConnection)
        .createOrUpdateFile(
            eq(PENDING_DELETE_DOMAINS_FILENAME),
            eq(MediaType.CSV_UTF_8),
            eq(folderId),
            bytesExportedToDrive.capture());
    assertThat(new String(bytesExportedToDrive.getValue(), UTF_8)).isEqualTo(content);
  }
}
