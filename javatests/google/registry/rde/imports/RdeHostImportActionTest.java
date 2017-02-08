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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.Response;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeHostImportAction}. */
@RunWith(MockitoJUnitRunner.class)
public class RdeHostImportActionTest extends MapreduceTestCase<RdeHostImportAction> {

  private static final ByteSource DEPOSIT_1_HOST = RdeImportsTestData.get("deposit_1_host.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  private MapreduceRunner mrRunner;
  private Response response;

  private final Optional<Integer> mapShards = Optional.absent();

  @Before
  public void before() throws Exception {
    createTld("test");
    response = new FakeResponse();
    mrRunner = new MapreduceRunner(Optional.<Integer>absent(), Optional.<Integer>absent());
    action = new RdeHostImportAction(
        mrRunner,
        response,
        IMPORT_BUCKET_NAME,
        IMPORT_FILE_NAME,
        mapShards);
  }

  @Test
  public void test_mapreduceSuccessfullyImportsHost() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    runMapreduce();
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    checkHost(hosts.get(0));
  }

  @Test
  public void test_mapreduceSuccessfullyCreatesHistoryEntry() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    runMapreduce();
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    HostResource host = hosts.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(host);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), host);
  }

  /** Ensures that a second pass on a host does not import a new host. */
  @Test
  public void test_mapreduceTwiceDoesNotDuplicateResources() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    // Create host and history entry first
    HostResource existingHost = persistResource(
        newHostResource("ns1.example1.test")
        .asBuilder()
        .setRepoId("Hns1_example1_test-TEST")
        .build());
    persistSimpleResource(createHistoryEntry(
        existingHost.getRepoId(),
        existingHost.getCurrentSponsorClientId(),
        loadHostXml(DEPOSIT_1_HOST)));
    // Simulate running a second import and verify that the resources
    // aren't imported twice (only one host, and one history entry)
    runMapreduce();
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    HostResource host = hosts.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(host);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), host);
  }

  /** Verify history entry fields are correct */
  private void checkHistoryEntry(HistoryEntry entry, HostResource parent) {
    assertThat(entry.getType()).isEqualTo(HistoryEntry.Type.RDE_IMPORT);
    assertThat(entry.getClientId()).isEqualTo(parent.getCurrentSponsorClientId());
    assertThat(entry.getXmlBytes().length).isGreaterThan(0);
    assertThat(entry.getBySuperuser()).isTrue();
    assertThat(entry.getReason()).isEqualTo("RDE Import");
    assertThat(entry.getRequestedByRegistrar()).isEqualTo(false);
    assertThat(entry.getParent()).isEqualTo(Key.create(parent));
  }

  /** Verifies that host id and ROID match expected values */
  private void checkHost(HostResource host) {
    assertThat(host.getFullyQualifiedHostName()).isEqualTo("ns1.example1.test");
    assertThat(host.getRepoId()).isEqualTo("Hns1_example1_test-TEST");
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
            new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
                .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  private static HistoryEntry createHistoryEntry(String roid, String clid, byte[] objectXml) {
    return new HistoryEntry.Builder()
        .setType(HistoryEntry.Type.RDE_IMPORT)
        .setClientId(clid)
        .setTrid(Trid.create(null))
        .setModificationTime(DateTime.now())
        .setXmlBytes(objectXml)
        .setBySuperuser(true)
        .setReason("RDE Import")
        .setRequestedByRegistrar(false)
        .setParent(Key.create(null, HostResource.class, roid))
        .build();
  }

  private static byte[] loadHostXml(ByteSource source) throws IOException {
    byte[] result = new byte[((int) source.size())];
    try (InputStream inStream = source.openStream()) {
      ByteStreams.readFully(inStream, result);
    }
    return result;
  }
}
