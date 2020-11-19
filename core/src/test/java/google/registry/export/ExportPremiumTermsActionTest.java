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
import static google.registry.export.ExportPremiumTermsAction.EXPORT_MIME_TYPE;
import static google.registry.export.ExportPremiumTermsAction.PREMIUM_TERMS_FILENAME;
import static google.registry.model.registry.label.PremiumListUtils.deletePremiumList;
import static google.registry.model.registry.label.PremiumListUtils.savePremiumListAndEntries;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
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
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import google.registry.request.Response;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.AppEngineExtension;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentMatchers;

/** Unit tests for {@link ExportPremiumTermsAction}. */
public class ExportPremiumTermsActionTest {

  private static final String DISCLAIMER_WITH_NEWLINE = "# Premium Terms Export Disclaimer\n";
  private static final ImmutableList<String> PREMIUM_NAMES =
      ImmutableList.of("2048,USD 549", "0,USD 549");
  private static final String EXPECTED_FILE_CONTENT =
      DISCLAIMER_WITH_NEWLINE + "0,USD 549.00\n" + "2048,USD 549.00\n";

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final DriveConnection driveConnection = mock(DriveConnection.class);
  private final Response response = mock(Response.class);

  private void runAction(String tld) {
    ExportPremiumTermsAction action = new ExportPremiumTermsAction();
    action.response = response;
    action.driveConnection = driveConnection;
    action.exportDisclaimer = DISCLAIMER_WITH_NEWLINE;
    action.tld = tld;
    action.run();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    PremiumList pl = new PremiumList.Builder().setName("pl-name").build();
    savePremiumListAndEntries(pl, PREMIUM_NAMES);
    persistResource(
        Registry.get("tld").asBuilder().setPremiumList(pl).setDriveFolderId("folder_id").build());
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
            PREMIUM_TERMS_FILENAME,
            EXPORT_MIME_TYPE,
            "folder_id",
            EXPECTED_FILE_CONTENT.getBytes(UTF_8));
    verifyNoMoreInteractions(driveConnection);

    verify(response).setStatus(SC_OK);
    verify(response).setPayload("file_id");
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void test_exportPremiumTerms_success_emptyPremiumList() throws IOException {
    PremiumList pl = new PremiumList.Builder().setName("pl-name").build();
    savePremiumListAndEntries(pl, ImmutableList.of());
    runAction("tld");

    verify(driveConnection)
        .createOrUpdateFile(
            PREMIUM_TERMS_FILENAME,
            EXPORT_MIME_TYPE,
            "folder_id",
            DISCLAIMER_WITH_NEWLINE.getBytes(UTF_8));
    verifyNoMoreInteractions(driveConnection);

    verify(response).setStatus(SC_OK);
    verify(response).setPayload("file_id");
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void test_exportPremiumTerms_doNothing_listNotConfigured() {
    persistResource(Registry.get("tld").asBuilder().setPremiumList(null).build());
    runAction("tld");

    verifyNoInteractions(driveConnection);
    verify(response).setStatus(SC_OK);
    verify(response).setPayload("No premium lists configured");
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void testExportPremiumTerms_doNothing_driveIdNotConfiguredInTld() {
    persistResource(Registry.get("tld").asBuilder().setDriveFolderId(null).build());
    runAction("tld");

    verifyNoInteractions(driveConnection);
    verify(response).setStatus(SC_OK);
    verify(response)
        .setPayload("Skipping export because no Drive folder is associated with this TLD");
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void test_exportPremiumTerms_failure_noSuchTld() {
    deleteTld("tld");
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verifyNoInteractions(driveConnection);
    verify(response).setStatus(SC_INTERNAL_SERVER_ERROR);
    verify(response).setPayload(anyString());
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void test_exportPremiumTerms_failure_noPremiumList() {
    deletePremiumList(new PremiumList.Builder().setName("pl-name").build());
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verifyNoInteractions(driveConnection);
    verify(response).setStatus(SC_INTERNAL_SERVER_ERROR);
    verify(response).setPayload("Could not load premium list for " + "tld");
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }

  @Test
  void testExportPremiumTerms_failure_driveIdThrowsException() throws IOException {
    persistResource(Registry.get("tld").asBuilder().setDriveFolderId("bad_folder_id").build());
    assertThrows(RuntimeException.class, () -> runAction("tld"));

    verify(driveConnection)
        .createOrUpdateFile(
            PREMIUM_TERMS_FILENAME,
            EXPORT_MIME_TYPE,
            "bad_folder_id",
            EXPECTED_FILE_CONTENT.getBytes(UTF_8));
    verifyNoMoreInteractions(driveConnection);
    verify(response).setStatus(SC_INTERNAL_SERVER_ERROR);
    verify(response).setPayload(
        ArgumentMatchers.contains("Error exporting premium terms file to Drive."));
    verify(response).setContentType(PLAIN_TEXT_UTF_8);
    verifyNoMoreInteractions(response);
  }
}
