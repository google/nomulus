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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.ExportDomainListsAction.REGISTERED_DOMAINS_FILENAME;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import google.registry.export.ExportDomainListsAction.ExportDomainListsReducer;
import google.registry.gcs.GcsUtils;
import google.registry.model.ofy.Ofy;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.storage.drive.DriveConnection;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ExportDomainListsAction}. */
@DualDatabaseTest
class ExportDomainListsActionTest extends MapreduceTestCase<ExportDomainListsAction> {

  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private DriveConnection driveConnection = mock(DriveConnection.class);
  private ArgumentCaptor<byte[]> bytesExportedToDrive = ArgumentCaptor.forClass(byte[].class);
  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));

  @Order(Order.DEFAULT - 1)
  @RegisterExtension
  public final InjectExtension inject =
      new InjectExtension().withStaticFieldOverride(Ofy.class, "clock", clock);

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    createTld("testtld");
    persistResource(Registry.get("tld").asBuilder().setDriveFolderId("brouhaha").build());
    persistResource(Registry.get("testtld").asBuilder().setTldType(TldType.TEST).build());

    ExportDomainListsReducer.setDriveConnectionForTesting(() -> driveConnection);

    action = new ExportDomainListsAction();
    action.mrRunner = makeDefaultRunner();
    action.response = response;
    action.gcsBucket = "outputbucket";
    action.gcsUtils = gcsUtils;
    action.clock = clock;
    action.driveConnection = driveConnection;
  }

  private void runAction() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void verifyExportedToDrive(String folderId, String domains) throws Exception {
    verify(driveConnection)
        .createOrUpdateFile(
            eq(REGISTERED_DOMAINS_FILENAME),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(folderId),
            bytesExportedToDrive.capture());
    assertThat(new String(bytesExportedToDrive.getValue(), UTF_8)).isEqualTo(domains);
  }

  @TestOfyOnly
  void test_writesLinkToMapreduceConsoleToResponse() throws Exception {
    runAction();
    assertThat(response.getPayload())
        .startsWith(
            "Mapreduce console: https://backend-dot-projectid.appspot.com"
                + "/_ah/pipeline/status.html?root=");
  }

  @TestOfyAndSql
  void test_outputsOnlyActiveDomains() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistDeletedDomain("mortuary.tld", DateTime.parse("2001-03-14T10:11:12Z"));
    runAction();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8);
    // Check that it only contains the active domains, not the dead one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    verifyExportedToDrive("brouhaha", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @TestOfyAndSql
  void test_outputsOnlyDomainsOnRealTlds() throws Exception {
    persistActiveDomain("onetwo.tld");
    persistActiveDomain("rudnitzky.tld");
    persistActiveDomain("wontgo.testtld");
    runAction();
    BlobId existingFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(existingFile), UTF_8).trim();
    // Check that it only contains the domains on the real TLD, and not the test one.
    assertThat(tlds).isEqualTo("onetwo.tld\nrudnitzky.tld");
    // Make sure that the test TLD file wasn't written out.
    BlobId nonexistentFile = BlobId.of("outputbucket", "testtld.txt");
    assertThrows(StorageException.class, () -> gcsUtils.readBytesFrom(nonexistentFile));
    ImmutableList<String> ls = gcsUtils.listFolderObjects("outputbucket", "");
    assertThat(ls).containsExactly("tld.txt");
    verifyExportedToDrive("brouhaha", "onetwo.tld\nrudnitzky.tld");
    verifyNoMoreInteractions(driveConnection);
  }

  @TestOfyAndSql
  void test_outputsDomainsFromDifferentTldsToMultipleFiles() throws Exception {
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
    runAction();
    BlobId firstTldFile = BlobId.of("outputbucket", "tld.txt");
    String tlds = new String(gcsUtils.readBytesFrom(firstTldFile), UTF_8).trim();
    assertThat(tlds).isEqualTo("dasher.tld\nprancer.tld");
    BlobId secondTldFile = BlobId.of("outputbucket", "tldtwo.txt");
    String moreTlds = new String(gcsUtils.readBytesFrom(secondTldFile), UTF_8).trim();
    assertThat(moreTlds).isEqualTo("buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    BlobId thirdTldFile = BlobId.of("outputbucket", "tldthree.txt");
    String evenMoreTlds = new String(gcsUtils.readBytesFrom(thirdTldFile), UTF_8).trim();
    assertThat(evenMoreTlds).isEqualTo("cupid.tldthree");
    verifyExportedToDrive("brouhaha", "dasher.tld\nprancer.tld");
    verifyExportedToDrive("hooray", "buddy.tldtwo\nrudolph.tldtwo\nsanta.tldtwo");
    // tldthree does not have a drive id, so no export to drive is performed.
    verifyNoMoreInteractions(driveConnection);
  }
}
