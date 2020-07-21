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

package google.registry.reporting.billing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GcsTestingUtils.writeGcsFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.common.net.MediaType;
import google.registry.gcs.GcsUtils;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link google.registry.reporting.billing.CopyDetailReportsAction}. */
@RunWith(JUnit4.class)
public class CopyDetailReportsActionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final GcsUtils gcsUtils = new GcsUtils(gcsService, 1024);

  private FakeResponse response;
  private DriveConnection driveConnection;
  private BillingEmailUtils emailUtils;
  private CopyDetailReportsAction action;

  @Before
  public void setUp() {
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setDriveFolderId("0B-12345").build());
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setDriveFolderId("0B-54321").build());
    response = new FakeResponse();
    driveConnection = mock(DriveConnection.class);
    emailUtils = mock(BillingEmailUtils.class);
    action =
        new CopyDetailReportsAction(
            "test-bucket",
            "results/",
            driveConnection,
            gcsUtils,
            new Retrier(new FakeSleeper(new FakeClock()), 3),
            response,
            emailUtils);
  }

  @Test
  public void testSuccess() throws IOException {
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_TheRegistrar_test.csv"),
        "hello,world\n1,2".getBytes(UTF_8));

    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_TheRegistrar_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));

    action.run();
    verify(driveConnection)
        .createOrUpdateFile(
            "invoice_details_2017-10_TheRegistrar_test.csv",
            MediaType.CSV_UTF_8,
            "0B-12345",
            "hello,world\n1,2".getBytes(UTF_8));

    verify(driveConnection)
        .createOrUpdateFile(
            "invoice_details_2017-10_TheRegistrar_hello.csv",
            MediaType.CSV_UTF_8,
            "0B-12345",
            "hola,mundo\n3,4".getBytes(UTF_8));
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Copied detail reports.\n");
  }

  @Test
  public void testSuccess_nonDetailReportFiles_notSent() throws IOException{
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_TheRegistrar_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));

    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/not_a_detail_report_2017-10_TheRegistrar_test.csv"),
        "hello,world\n1,2".getBytes(UTF_8));
    action.run();
    verify(driveConnection)
        .createOrUpdateFile(
            "invoice_details_2017-10_TheRegistrar_hello.csv",
            MediaType.CSV_UTF_8,
            "0B-12345",
            "hola,mundo\n3,4".getBytes(UTF_8));
    // Verify we didn't copy the non-detail report file.
    verifyNoMoreInteractions(driveConnection);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Copied detail reports.\n");
  }

  @Test
  public void testSuccess_transientIOException_retries() throws IOException {
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_TheRegistrar_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));
    when(driveConnection.createOrUpdateFile(any(), any(), any(), any()))
        .thenThrow(new IOException("expected"))
        .thenReturn("success");

    action.run();
    verify(driveConnection, times(2))
        .createOrUpdateFile(
            "invoice_details_2017-10_TheRegistrar_hello.csv",
            MediaType.CSV_UTF_8,
            "0B-12345",
            "hola,mundo\n3,4".getBytes(UTF_8));
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Copied detail reports.\n");
  }

  @Test
  public void testFail_tooManyFailures_sendsAlertEmail_continues() throws IOException {
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_TheRegistrar_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_NewRegistrar_test.csv"),
        "hello,world\n1,2".getBytes(UTF_8));
    when(driveConnection.createOrUpdateFile(
            eq("invoice_details_2017-10_TheRegistrar_hello.csv"), any(), any(), any()))
        .thenThrow(new IOException("expected"));

    action.run();
    verify(driveConnection, times(3))
        .createOrUpdateFile(
            "invoice_details_2017-10_TheRegistrar_hello.csv",
            MediaType.CSV_UTF_8,
            "0B-12345",
            "hola,mundo\n3,4".getBytes(UTF_8));
    verify(driveConnection)
        .createOrUpdateFile(
            "invoice_details_2017-10_NewRegistrar_test.csv",
            MediaType.CSV_UTF_8,
            "0B-54321",
            "hello,world\n1,2".getBytes(UTF_8));
    verify(emailUtils)
        .sendAlertEmail(
            "Copied detail reports.\n"
                + "The following errors were encountered:\n"
                + "Registrar: TheRegistrar\n"
                + "Error: java.io.IOException: expected\n");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload())
        .isEqualTo(
            "Copied detail reports.\n"
                + "The following errors were encountered:\n"
                + "Registrar: TheRegistrar\n"
                + "Error: java.io.IOException: expected\n");
  }

  @Test
  public void testFail_registrarDoesntExist_doesntCopy() throws IOException {
    writeGcsFile(
        gcsService,
        new GcsFilename("test-bucket", "results/invoice_details_2017-10_notExistent_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));
    action.run();
    verifyNoInteractions(driveConnection);
  }

  @Test
  public void testFail_noRegistrarFolderId_doesntCopy() throws IOException {
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setDriveFolderId(null).build());
    writeGcsFile(
        gcsService,
        new GcsFilename(
            "test-bucket", "results/invoice_details_2017-10_TheRegistrar_hello.csv"),
        "hola,mundo\n3,4".getBytes(UTF_8));
    action.run();
    verifyNoInteractions(driveConnection);
  }
}
