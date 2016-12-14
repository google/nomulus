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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeContactInput} */
@RunWith(MockitoJUnitRunner.class)
public class RdeContactInputTest {

  private static final ByteSource DEPOSIT_0_CONTACT =
      RdeImportsTestData.get("deposit_0_contact_header.xml");
  private static final ByteSource DEPOSIT_1_CONTACT =
      RdeImportsTestData.get("deposit_1_contact.xml");
  private static final ByteSource DEPOSIT_199_CONTACT =
      RdeImportsTestData.get("deposit_199_contact_header.xml");
  private static final ByteSource DEPOSIT_200_CONTACT =
      RdeImportsTestData.get("deposit_200_contact_header.xml");
  private static final ByteSource DEPOSIT_1000_CONTACT =
      RdeImportsTestData.get("deposit_1000_contact_header.xml");
  private static final ByteSource DEPOSIT_10000_CONTACT =
      RdeImportsTestData.get("deposit_10000_contact_header.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  /** Escrow file with zero contacts results in one reader */
  @Test
  public void testZeroContactsDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_0_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with zero contacts results in expected reader configuration */
  @Test
  public void testZeroContactsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_0_CONTACT);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
  }

  /** Escrow file with zero contacts and 75 shards results in one reader */
  @Test
  public void testZeroContacts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_0_CONTACT);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with one contact results in one reader */
  @Test
  public void testOneContactDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_1_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with one contact results in expected reader configuration */
  @Test
  public void testOneContactDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_1_CONTACT);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
  }

  /** Escrow file with one contact and 75 shards results in one reader */
  @Test
  public void testOneContact75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_1_CONTACT);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with 199 contacts results in one reader */
  @Test
  public void test199ContactsDefaultShards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_199_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 1);
  }

  /** Escrow file with 199 contacts results in expected reader configuration */
  @Test
  public void test199ContactsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_199_CONTACT);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 199);
  }

  /** Escrow file with 199 contacts and 75 shards results in one reader */
  @Test
  public void test199Contacts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_199_CONTACT);
    assertNumberOfReaders(Optional.of(75), 1);
  }

  /** Escrow file with 200 contacts results in two readers */
  @Test
  public void test200ContactsDefaultShards_returnsTwoReaders() throws Exception {
    pushToGcs(DEPOSIT_200_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 2);
  }

  /** Escrow file with 200 contacts results in expected reader configurations */
  @Test
  public void test200ContactsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_200_CONTACT);
    assertReaderConfigurations(Optional.<Integer>absent(), 0, 0, 100);
    assertReaderConfigurations(Optional.<Integer>absent(), 1, 100, 100);
  }

  /** Escrow file with 200 contacts and 75 shards results in two readers */
  @Test
  public void test200Contacts75Shards_returnsOneReader() throws Exception {
    pushToGcs(DEPOSIT_200_CONTACT);
    assertNumberOfReaders(Optional.of(75), 2);
  }

  /** Escrow file with 1000 contacts results in ten readers */
  @Test
  public void test1000ContactsDefaultShards_returns10Readers() throws Exception {
    pushToGcs(DEPOSIT_1000_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 10);
  }

  /** Escrow file with 1000 contacts results in expected reader configurations */
  @Test
  public void test1000ContactsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_1000_CONTACT);
    for (int i = 0; i < 10; i++) {
      assertReaderConfigurations(Optional.<Integer>absent(), i, i * 100, 100);
    }
  }

  /** Escrow file with 1000 contacts and 75 shards results in ten readers */
  @Test
  public void test1000Contacts75Shards_returns10Readers() throws Exception {
    pushToGcs(DEPOSIT_1000_CONTACT);
    assertNumberOfReaders(Optional.of(75), 10);
  }

  /** Escrow file with 10000 contacts results in 50 readers */
  @Test
  public void test10000ContactsDefaultShards_returns50Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_CONTACT);
    assertNumberOfReaders(Optional.<Integer>absent(), 50);
  }

  /** Escrow file with 10000 contacts results in expected reader configurations */
  @Test
  public void test10000ContactsDefaultShardsReaderConfigurations() throws Exception {
    pushToGcs(DEPOSIT_10000_CONTACT);
    for (int i = 0; i < 50; i++) {
      assertReaderConfigurations(Optional.<Integer>absent(), i, i * 200, 200);
    }
  }

  /** Escrow file with 10000 contacts and 75 shards results in 75 readers */
  @Test
  public void test10000Contacts75Shards_returns75Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_CONTACT);
    assertNumberOfReaders(Optional.of(75), 75);
  }

  /** Escrow file with 10000 contacts and 150 shards results in 100 readers */
  @Test
  public void test10000Contacts150Shards_returns100Readers() throws Exception {
    pushToGcs(DEPOSIT_10000_CONTACT);
    assertNumberOfReaders(Optional.of(150), 100);
  }

  /**
   * Verify bucket, filename, offset and max results for a specific reader
   *
   * @param numberOfShards Number of desired shards ({@code Optional.absent()} uses default of 50)
   * @param whichReader Index of the reader in the list that is produced by the
   *        {@link RdeContactInput}
   * @param expectedOffset Expected offset of the reader
   * @param expectedMaxResults Expected maxResults of the reader
   */
  private void assertReaderConfigurations(
      Optional<Integer> numberOfShards,
      int whichReader,
      int expectedOffset,
      int expectedMaxResults) throws Exception {
    RdeContactInput input = getInput(numberOfShards);
    List<?> readers = input.createReaders();
    RdeContactReader reader = (RdeContactReader) readers.get(whichReader);
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
   * Verify the number of readers produced by the {@link RdeContactInput}
   *
   * @param numberOfShards Number of desired shards ({@code Optional.absent()} uses default of 50)
   * @param expectedNumberOfReaders Expected size of the list returned
   */
  private void assertNumberOfReaders(Optional<Integer> numberOfShards,
      int expectedNumberOfReaders) throws Exception {
    RdeContactInput input = getInput(numberOfShards);
    List<?> readers = input.createReaders();
    assertThat(readers).hasSize(expectedNumberOfReaders);
  }

  /**
   * Creates a new testable instance of {@link RdeContactInput}
   * @param mapShards Number of desired shards ({@code Optional.absent()} uses default of 50)
   */
  private RdeContactInput getInput(Optional<Integer> mapShards) {
    return new RdeContactInput(mapShards, IMPORT_BUCKET_NAME, IMPORT_FILE_NAME);
  }

  /**
   * Verifies the configured import bucket and file names.
   */
  private void assertImportBucketAndFilename(RdeContactReader reader) {
    assertThat(reader.importBucketName).isEqualTo("import-bucket");
    assertThat(reader.importFileName).isEqualTo("escrow-file.xml");
  }
}
