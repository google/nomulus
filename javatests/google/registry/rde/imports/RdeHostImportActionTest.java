// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import google.registry.config.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.host.HostResource;
import google.registry.request.Response;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
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
}
