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

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import google.registry.config.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.NoSuchElementException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeHostReader} */
@RunWith(MockitoJUnitRunner.class)
public class RdeHostReaderTest {

  private static final ByteSource DEPOSIT_1_HOST = RdeImportsTestData.get("deposit_1_host.xml");
  private static final ByteSource DEPOSIT_3_HOST = RdeImportsTestData.get("deposit_3_host.xml");
  private static final ByteSource DEPOSIT_4_HOST = RdeImportsTestData.get("deposit_4_host.xml");
  private static final ByteSource DEPOSIT_10_HOST = RdeImportsTestData.get("deposit_10_host.xml");
  private static final String IMPORT_BUCKET_NAME = "rde-import";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  /** Reads at least one result at 0 offset 1 maxResults */
  @Test
  public void testZeroOffsetOneResult_readsOne() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    RdeHostReader reader = getReader(0, 1);
    HostResource host1 = reader.next();
    checkHost(host1, "ns1.example1.test", "Hns1_example1_test-TEST");
  }

  /** Reads at most one at 0 offset 1 maxResults */
  @Test
  public void testZeroOffsetOneResult_stopsAfterOne() throws Exception {
    pushToGcs(DEPOSIT_3_HOST);
    RdeHostReader reader = getReader(0, 1);
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Skips already-processed records after rehydration */
  @Test
  public void testZeroOffsetOneResult_skipsOneAfterRehydration() throws Exception {
    pushToGcs(DEPOSIT_3_HOST);
    RdeHostReader reader = getReader(0, 1);
    reader.next();
    reader.endSlice();

    reader = cloneReader(reader);
    reader.beginSlice();
    // reader will not advance any further
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Reads three hosts */
  @Test
  public void testZeroOffsetThreeResult_readsThree() throws Exception {
    pushToGcs(DEPOSIT_3_HOST);
    RdeHostReader reader = getReader(0, 3);
    checkHost(reader.next(), "ns1.example1.test", "Hns1_example1_test-TEST");
    checkHost(reader.next(), "ns1.example2.test", "Hns1_example2_test-TEST");
    checkHost(reader.next(), "ns1.example3.test", "Hns1_example3_test-TEST");
  }

  /** Stops reading at 3 maxResults */
  @Test
  public void testZeroOffsetThreeResult_stopsAtThree() throws Exception {
    pushToGcs(DEPOSIT_4_HOST);
    RdeHostReader reader = getReader(0, 3);
    for (int i = 0; i < 3; i++) {
      reader.next();
    }
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Reads one host from file then stops at end of file */
  @Test
  public void testZeroOffsetThreeResult_endOfFile() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    RdeHostReader reader = getReader(0, 3);
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  /** Skips three hosts with offset of three */
  @Test
  public void testThreeOffsetOneResult_skipsThree() throws Exception {
    pushToGcs(DEPOSIT_4_HOST);
    RdeHostReader reader = getReader(3, 1);
    checkHost(reader.next(), "ns1.example4.test", "Hns1_example4_test-TEST");
  }

  /** Skips four hosts after advancing once at three offset, then rehydrating */
  @Test
  public void testThreeOffsetTwoResult_skipsFourAfterRehydration() throws Exception {
    pushToGcs(DEPOSIT_10_HOST);
    RdeHostReader reader = getReader(3, 2);
    reader.next();
    reader.endSlice();
    reader = cloneReader(reader);
    reader.beginSlice();
    checkHost(reader.next(), "ns1.example5.test", "Hns1_example5_test-TEST");
  }

  /** Reads three at zero offset three results with rehydration in the middle */
  @Test
  public void testZeroOffsetThreeResult_readsThreeWithRehydration() throws Exception {
    pushToGcs(DEPOSIT_4_HOST);
    RdeHostReader reader = getReader(0, 3);
    checkHost(reader.next(), "ns1.example1.test", "Hns1_example1_test-TEST");
    reader.endSlice();
    reader = cloneReader(reader);
    reader.beginSlice();
    checkHost(reader.next(), "ns1.example2.test", "Hns1_example2_test-TEST");
    checkHost(reader.next(), "ns1.example3.test", "Hns1_example3_test-TEST");
  }

  /** Stops reading at three with zero offset three results with rehydration in the middle */
  @Test
  public void testZeroOffsetThreeResult_stopsAtThreeWithRehydration() throws Exception {
    pushToGcs(DEPOSIT_4_HOST);
    RdeHostReader reader = getReader(0, 3);
    reader.next();
    reader.endSlice();
    reader = cloneReader(reader);
    reader.beginSlice();
    reader.next();
    reader.next();
    thrown.expect(NoSuchElementException.class);
    reader.next();
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
          .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  /** Creates a deep copy of the {@link RdeHostReader} */
  private RdeHostReader cloneReader(
      RdeHostReader reader) throws Exception {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    ObjectOutputStream oout = new ObjectOutputStream(bout);
    oout.writeObject(reader);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    ObjectInputStream oin = new ObjectInputStream(bin);
    RdeHostReader result = (RdeHostReader) oin.readObject();
    return result;
  }

  /** Verifies that domain name and ROID match expected values */
  private void checkHost(HostResource host, String domainName, String repoId) {
    assertThat(host).isNotNull();
    assertThat(host.getFullyQualifiedHostName()).isEqualTo(domainName);
    assertThat(host.getRepoId()).isEqualTo(repoId);
  }

  /** Gets a new {@link RdeHostReader} with specified offset and maxResults */
  private RdeHostReader getReader(int offset, int maxResults) throws Exception {
    RdeHostReader reader =
        new RdeHostReader(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME, offset, maxResults);
    reader.beginSlice();
    return reader;
  }
}
