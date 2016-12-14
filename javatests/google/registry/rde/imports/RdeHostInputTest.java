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
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import google.registry.config.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeHostInput} */
@RunWith(JUnit4.class)
public class RdeHostInputTest {

  private static final ByteSource DEPOSIT_0_HOST =
      RdeImportsTestData.get("deposit_0_host_header.xml");
  private static final ByteSource DEPOSIT_1_HOST = RdeImportsTestData.get("deposit_1_host.xml");
  private static final ByteSource DEPOSIT_199_HOST =
      RdeImportsTestData.get("deposit_199_host_header.xml");
  private static final ByteSource DEPOSIT_200_HOST =
      RdeImportsTestData.get("deposit_200_host_header.xml");
  private static final ByteSource DEPOSIT_1000_HOST =
      RdeImportsTestData.get("deposit_1000_host_header.xml");
  private static final ByteSource DEPOSIT_10000_HOST =
      RdeImportsTestData.get("deposit_10000_host_header.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  /** Number of shards cannot be negative */
  @Test
  public void testNegativeShards_throwsIllegalArgumentException() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Number of shards must be greater than zero");
    getInput(Optional.of(-1));
  }

  /** Number of shards cannot be zero */
  @Test
  public void testZeroShards_throwsIllegalArgumentException() throws Exception {
    thrown.expect(IllegalArgumentException.class, "Number of shards must be greater than zero");
    getInput(Optional.of(0));
  }

  /** Escrow file with zero hosts results in one reader */
  @Test
  public void testZeroHostsDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_0_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with zero hosts results in expected reader configuration */
  @Test
  public void testZeroHostsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_0_HOST);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
  }

  /** Escrow file with zero hosts and 75 shards results in one reader */
  @Test
  public void testZeroHosts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_0_HOST);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with one host results in one reader */
  @Test
  public void testOneHostDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with one host results in expected reader configuration */
  @Test
  public void testOneHostDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
  }

  /** Escrow file with one host and 75 shards results in one reader */
  @Test
  public void testOneHost75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_1_HOST);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with 199 hosts results in one reader */
  @Test
  public void test199HostsDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_199_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with 199 hosts results in expected reader configuration */
  @Test
  public void test199HostsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_199_HOST);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 199);
  }

  /** Escrow file with 199 hosts and 75 shards results in one reader */
  @Test
  public void test199Hosts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_199_HOST);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with 200 hosts results in two readers */
  @Test
  public void test200HostsDefaultShards_returnsTwoReaders() throws Exception {
    pushToGcs(DEPOSIT_200_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 2);
  }

  /** Escrow file with 200 hosts results in expected reader configurations */
  @Test
  public void test200HostsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_200_HOST);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
    assertReaderConfigurations(Optional.<Integer>absent(), 1, 100, 100);
  }

  /** Escrow file with 200 hosts and 75 shards results in two readers */
  @Test
  public void test200Hosts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_200_HOST);
    assertNumberOfReaders(Optional.of(75), 2);
  }

  /** Escrow file with 1000 hosts results in ten readers */
  @Test
  public void test1000HostsDefaultShards_returns10Readers() throws Exception {
    pushToGcs(DEPOSIT_1000_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 10);
  }

  /** Escrow file with 1000 hosts results in expected reader configurations */
  @Test
  public void test1000HostsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_1000_HOST);
    for (int i = 0; i < 10; i++) {
      assertReaderConfigurations(Optional.<Integer>absent(), i, i * 100, 100);
    }
  }

  /** Escrow file with 1000 hosts and 75 shards results in ten readers */
  @Test
  public void test1000Hosts75Shards_returns10Readers() throws Exception {
    pushToGcs(DEPOSIT_1000_HOST);
    assertNumberOfReaders(Optional.of(75), 10);
  }

  /** Escrow file with 10000 hosts results in 50 readers */
  @Test
  public void test10000HostsDefaultShards_returns50Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_HOST);
    assertNumberOfReaders(Optional.<Integer>absent(), 50);
  }

  /** Escrow file with 10000 hosts results in expected reader configurations */
  @Test
  public void test10000HostsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_10000_HOST);
    for (int i = 0; i < 50; i++) {
      assertReaderConfigurations(Optional.<Integer>absent(), i, i * 200, 200);
    }
  }

  /** Escrow file with 10000 hosts and 75 shards results in 75 readers */
  @Test
  public void test10000Hosts75Shards_returns75Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_HOST);
    assertNumberOfReaders(Optional.of(75), 75);
  }

  /** Escrow file with 10000 hosts and 150 shards results in 100 readers */
  @Test
  public void test10000Hosts150Shards_returns100Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_HOST);
    assertNumberOfReaders(Optional.of(150), 100);
  }

  /**
   * Verify bucket, filename, offset and max results for a specific reader
   *
   * @param numberOfShards Number of desired shards ({@link Optional#absent} uses default of 50)
   * @param whichReader Index of the reader in the list that is produced by the {@link RdeHostInput}
   * @param expectedOffset Expected offset of the reader
   * @param expectedMaxResults Expected maxResults of the reader
   */
  private void assertReaderConfigurations(
      Optional<Integer> numberOfShards, int whichReader, int expectedOffset, int expectedMaxResults)
      throws Exception {
    RdeHostInput input = getInput(numberOfShards);
    List<?> readers = input.createReaders();
    RdeHostReader reader = (RdeHostReader) readers.get(whichReader);
    assertImportBucketAndFilename(reader);
    assertThat(reader.offset).isEqualTo(expectedOffset);
    assertThat(reader.maxResults).isEqualTo(expectedMaxResults);
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
          .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  /**
   * Verify the number of readers produced by the {@link RdeHostInput}
   *
   * @param numberOfShards Number of desired shards ({@link Optional#absent} uses default of 50)
   * @param expectedNumberOfReaders Expected size of the list returned
   */
  private void assertNumberOfReaders(Optional<Integer> numberOfShards, int expectedNumberOfReaders)
      throws Exception {
    RdeHostInput input = getInput(numberOfShards);
    List<?> readers = input.createReaders();
    assertThat(readers).hasSize(expectedNumberOfReaders);
  }

  /**
   * Creates a new testable instance of {@link RdeHostInput}
   * @param mapShards Number of desired shards ({@link Optional#absent} uses default of 50)
   */
  private RdeHostInput getInput(Optional<Integer> mapShards) {
    return new RdeHostInput(mapShards, IMPORT_BUCKET_NAME, IMPORT_FILE_NAME);
  }

  /**
   * Verifies the configured import bucket and file names.
   */
  private void assertImportBucketAndFilename(RdeHostReader reader) {
    assertThat(reader.importBucketName).isEqualTo("import-bucket");
    assertThat(reader.importFileName).isEqualTo("escrow-file.xml");
  }
}
