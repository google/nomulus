// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.ExportPremiumTermsAction.EXPORT_MIME_TYPE;
import static google.registry.export.ExportPremiumTermsAction.TLD_IDENTIFIER_FORMAT;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.FakeResponse;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ExportPremiumTermsAction}. */
public class ExportPremiumTermsActionTest {

  private static final String DISCLAIMER_WITH_NEWLINE = "# Premium Terms Export Disclaimer\n";
  private static final String TLD_IDENTIFIER_WITH_NEWLINE =
      String.format(TLD_IDENTIFIER_FORMAT, "tld") + "\n";
  private static final ImmutableList<String> PREMIUM_NAMES =
      ImmutableList.of("2048,USD 549", "0,USD 549");
  private static final String EXPECTED_FILE_CONTENT =
      DISCLAIMER_WITH_NEWLINE + TLD_IDENTIFIER_WITH_NEWLINE + "0, 549.00\n" + "2048, 549.00\n";

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final DriveConnection driveConnection = mock(DriveConnection.class);
  private final FakeResponse response = new FakeResponse();

  private void runAction(String tld) {
    ExportPremiumTermsAction action = new ExportPremiumTermsAction();
    action.response = response;
    action.driveConnection = driveConnection;
    action.exportDisclaimer = DISCLAIMER_WITH_NEWLINE;
    action.tldStr = tld;
    action.run();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    PremiumList pl = PremiumListDao.save("pl-name", USD, PREMIUM_NAMES);
    persistResource(
        Tld.get("tld").asBuilder().setPremiumList(pl).setDriveFolderId("folder_id").build());
    when(driveConnection.createOrUpdateFile(
            anyString(), any(MediaType.class), eq("folder_id"), any(byte[].class)))
        .thenReturn("file_id");
    when(driveConnection.createOrUpdateFile(
            anyString(), any(MediaType.class), eq("bad_folder_id"), any(byte[].class)))
        .thenThrow(new IOException());
  }

  @Test
  void test_exportPremiumTerms_success() throws IOException {
    runAction("tld");

    verify(driveConnection)
        .createOrUpdateFile(
            "CONFIDENTIAL_premium_terms_tld.txt",
            EXPORT_MIME_TYPE,
            "folder_id",
            EXPECTED_FILE_CONTENT.getBytes(UTF_8));
    verifyNoMoreInteractions(driveConnection);

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("file_id");
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }

  @Test
  void test_exportPremiumTerms_doNothing_listNotConfigured() {
    persistResource(Tld.get("tld").asBuilder().setPremiumList(null).build());
    runAction("tld");

    verifyNoInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("No premium lists configured");
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }

  @Test
  void testExportPremiumTerms_doNothing_driveIdNotConfiguredInTld() {
    persistResource(Tld.get("tld").asBuilder().setDriveFolderId(null).build());
    runAction("tld");

    verifyNoInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo("Skipping export because no Drive folder is associated with this TLD");
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }

  @Test
  void test_exportPremiumTerms_failure_noSuchTld() {
    deleteTld("tld");
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verifyNoInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).isNotEmpty();
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }

  @Test
  void test_exportPremiumTerms_failure_noPremiumList() {
    PremiumListDao.delete(new PremiumList.Builder().setName("pl-name").build());
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verifyNoInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).isEqualTo("Could not load premium list for " + "tld");
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }

  @Test
  void testExportPremiumTerms_failure_driveIdThrowsException() throws IOException {
    persistResource(Tld.get("tld").asBuilder().setDriveFolderId("bad_folder_id").build());
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verify(driveConnection)
        .createOrUpdateFile(
            "CONFIDENTIAL_premium_terms_tld.txt",
            EXPORT_MIME_TYPE,
            "bad_folder_id",
            EXPECTED_FILE_CONTENT.getBytes(UTF_8));
    verifyNoMoreInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("Error exporting premium terms file to Drive.");
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
  }
}
