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

package google.registry.export;

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.ExportDomainListsAction.ExportDomainListsReducer.EXPORT_MIME_TYPE;
import static google.registry.export.ExportDomainListsAction.ExportDomainListsReducer.REGISTERED_DOMAINS_FILENAME;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.ListOptions;
import com.google.appengine.tools.cloudstorage.ListResult;
import google.registry.export.ExportDomainListsAction.ExportDomainListsReducer;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.FileNotFoundException;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ExportDomainListsAction}. */
@RunWith(JUnit4.class)
public class ExportDomainListsActionTest extends MapreduceTestCase<ExportDomainListsAction> {

  private GcsService gcsService;
  private DriveConnection driveConnection = mock(DriveConnection.class);
  private ArgumentCaptor<byte[]> bytesExportedToDrive = ArgumentCaptor.forClass(byte[].class);
  private final FakeResponse response = new FakeResponse();

  @Before
  public void init() {
    createTld("tld");
    createTld("testtld");
    persistResource(Registry.get("tld").asBuilder().setDriveFolderId("brouhaha").build());
    persistResource(Registry.get("testtld").asBuilder().setTldType(TldType.TEST).build());

    ExportDomainListsReducer.setDriveConnectionForTesting(() -> driveConnection);

    action = new ExportDomainListsAction();
    action.mrRunner = makeDefaultRunner();
    action.response = response;
    action.gcsBucket = "outputbucket";
    action.gcsBufferSize = 500;
    gcsService = createGcsService();
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void verifyExportedToDrive(String folderId, String domains) throws Exception {
    verify(driveConnection)
        .createOrUpdateFile(
            eq(REGISTERED_DOMAINS_FILENAME),
            eq(EXPORT_MIME_TYPE),
            eq(folderId),
            bytesExportedToDrive.capture());
    assertThat(new String(bytesExportedToDrive.getValue(), UTF_8)).isEqualTo(domains);
  }

  @Test
  public void test_writesLinkToMapreduceConsoleToResponse() throws Exception {
    runMapreduce();
    assertThat(response.getPayload())
        .startsWith(
            "Mapreduce console: https://backend-dot-projectid.appspot.com"
                + "/_ah/pipeline/status.html?root=");
  }

  @Test
  public void test_outputsOnlyActiveDomains() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistDeletedDomain("mortuary.tld", DateTime.parse("2001-03-14T10:11:12Z"));
    runMapreduce();
    GcsFilename existingFile = new GcsFilename("outputbucket", "tld.txt");
    String tlds = new String(readGcsFile(gcsService, existingFile), UTF_8);
    // Check that it only contains the active domains, not the dead one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    verifyExportedToDrive("brouhaha", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  public void test_outputsOnlyDomainsOnRealTlds() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistActiveDomain("wontgo.testtld");
    runMapreduce();
    GcsFilename existingFile = new GcsFilename("outputbucket", "tld.txt");
    String tlds = new String(readGcsFile(gcsService, existingFile), UTF_8).trim();
    // Check that it only contains the domains on the real TLD, and not the test one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    // Make sure that the test TLD file wasn't written out.
    GcsFilename nonexistentFile = new GcsFilename("outputbucket", "testtld.txt");
    assertThrows(FileNotFoundException.class, () -> readGcsFile(gcsService, nonexistentFile));
    ListResult ls = gcsService.list("outputbucket", ListOptions.DEFAULT);
    assertThat(ls.next().getName()).isEqualTo("tld.txt");
    // Make sure that no other files were written out.
    assertThat(ls.hasNext()).isFalse();
    verifyExportedToDrive("brouhaha", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @Test
  public void test_outputsDomainsFromDifferentTldsToMultipleFiles() throws Exception {
    createTld("tldtwo");
    persistResource(Registry.get("tldtwo").asBuilder().setDriveFolderId("hooray").build());

    createTld("tldthree");
    // You'd think this test was written around Christmas, but it wasn't.
    persistActiveDomain("dasher.tld");
    persistActiveDomain("prancer.tld");
    persistActiveDomain("rudolph.tldtwo");
    persistActiveDomain("santa.tldtwo");
    persistActiveDomain("buddy.tldtwo");
    persistActiveDomain("cupid.tldthree");
    runMapreduce();
    GcsFilename firstTldFile = new GcsFilename("outputbucket", "tld.txt");
    String tlds = new String(readGcsFile(gcsService, firstTldFile), UTF_8).trim();
    assertThat(tlds).isEqualTo("dasher.tld\nprancer.tld");
    GcsFilename secondTldFile = new GcsFilename("outputbucket", "tldtwo.txt");
    String moreTlds = new String(readGcsFile(gcsService, secondTldFile), UTF_8).trim();
    assertThat(moreTlds).isEqualTo("buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    GcsFilename thirdTldFile = new GcsFilename("outputbucket", "tldthree.txt");
    String evenMoreTlds = new String(readGcsFile(gcsService, thirdTldFile), UTF_8).trim();
    assertThat(evenMoreTlds).isEqualTo("cupid.tldthree");
    verifyExportedToDrive("brouhaha", "dasher.tld\nprancer.tld");
    verifyExportedToDrive("hooray", "buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    // tldthree does not have a drive id, so no export to drive is performed.
    verifyNoMoreInteractions(driveConnection);
  }
}
