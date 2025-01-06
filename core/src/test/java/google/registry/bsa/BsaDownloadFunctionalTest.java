// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.BsaTestingUtils.createDownloadScheduler;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.Duration.standardDays;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.api.BsaReportSender;
import google.registry.gcs.GcsUtils;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.Tlds;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Functional tests of BSA block list download and processing. */
@ExtendWith(MockitoExtension.class)
class BsaDownloadFunctionalTest {

  static final DateTime TEST_START_TIME = DateTime.parse("2024-01-01T00:00:00Z");
  static final String BSA_CSV_HEADER = "domainLabel,orderIDs";
  @Mock BlockListFetcher blockListFetcher;
  @Mock BsaReportSender bsaReportSender;

  @Mock BsaEmailSender bsaEmailSender;

  private final FakeClock fakeClock = new FakeClock(TEST_START_TIME);

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private GcsClient gcsClient;
  private BsaDownloadAction action;
  private Response response;

  @BeforeEach
  void setup() throws Exception {
    createTlds("app", "dev");
    Tlds.getTldEntitiesOfType(TldType.REAL)
        .forEach(
            tld ->
                persistResource(
                    tld.asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build()));
    gcsClient =
        new GcsClient(new GcsUtils(LocalStorageHelper.getOptions()), "my-bucket", "SHA-256");
    response = new FakeResponse();
    action =
        new BsaDownloadAction(
            createDownloadScheduler(fakeClock),
            blockListFetcher,
            new BsaDiffCreator(gcsClient),
            bsaReportSender,
            gcsClient,
            () -> new IdnChecker(fakeClock),
            bsaEmailSender,
            new BsaLock(
                new FakeLockHandler(/* lockSucceeds= */ true), Duration.standardSeconds(30)),
            fakeClock,
            /* transactionBatchSize= */ 5,
            response);
  }

  @Test
  void initialDownload_noUnblockables() throws Exception {
    LazyBlockList blockList = mockBlockList(BlockListType.BLOCK, ImmutableList.of("abc,1"));
    LazyBlockList blockPlusList =
        mockBlockList(BlockListType.BLOCK_PLUS, ImmutableList.of("abc,2", "def,3"));
    mockBlockListFetcher(blockList, blockPlusList);
    action.run();
    String downloadJob = "2024-01-01t000000.000z";
    try (Stream<String> blockListFile = gcsClient.readBlockList(downloadJob, BlockListType.BLOCK)) {
      assertThat(blockListFile).containsExactly(BSA_CSV_HEADER, "abc,1").inOrder();
    }
    try (Stream<String> blockListFile =
        gcsClient.readBlockList(downloadJob, BlockListType.BLOCK_PLUS)) {
      assertThat(blockListFile).containsExactly(BSA_CSV_HEADER, "abc,2", "def,3");
    }
    // TODO(weiminyu): check intermediate files
    assertThat(getPersistedLabels()).containsExactly("abc", "def");
  }

  @Test
  void initialDownload_thenDeleteLabel_noUnblockables() throws Exception {
    LazyBlockList blockList = mockBlockList(BlockListType.BLOCK, ImmutableList.of("abc,1"));
    LazyBlockList blockPlusList =
        mockBlockList(BlockListType.BLOCK_PLUS, ImmutableList.of("abc,2", "def,3"));
    LazyBlockList blockList2 = mockBlockList(BlockListType.BLOCK, ImmutableList.of("abc,1"));
    LazyBlockList blockPlusList2 =
        mockBlockList(BlockListType.BLOCK_PLUS, ImmutableList.of("abc,2"));
    mockBlockListFetcher(blockList, blockPlusList, blockList2, blockPlusList2);
    action.run();
    assertThat(getPersistedLabels()).containsExactly("abc", "def");
    fakeClock.advanceBy(standardDays(1));
    action.run();
    assertThat(getPersistedLabels()).containsExactly("abc");
  }

  private ImmutableList<String> getPersistedLabels() {
    return ImmutableList.copyOf(
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery("SELECT label from \"BsaLabel\"")
                        .getResultList()));
  }

  private void mockBlockListFetcher(LazyBlockList blockList, LazyBlockList blockPlusList)
      throws Exception {
    when(blockListFetcher.fetch(BlockListType.BLOCK)).thenReturn(blockList);
    when(blockListFetcher.fetch(BlockListType.BLOCK_PLUS)).thenReturn(blockPlusList);
  }

  private void mockBlockListFetcher(
      LazyBlockList blockList1,
      LazyBlockList blockPlusList1,
      LazyBlockList blockList2,
      LazyBlockList blockPlusList2)
      throws Exception {
    when(blockListFetcher.fetch(BlockListType.BLOCK)).thenReturn(blockList1, blockList2);
    when(blockListFetcher.fetch(BlockListType.BLOCK_PLUS))
        .thenReturn(blockPlusList1, blockPlusList2);
  }

  static LazyBlockList mockBlockList(BlockListType blockListType, ImmutableList<String> dataLines)
      throws Exception {
    byte[] bytes =
        Joiner.on('\n')
            .join(new ImmutableList.Builder<String>().add(BSA_CSV_HEADER).addAll(dataLines).build())
            .getBytes(UTF_8);
    String checksum = generateChecksum(bytes);
    LazyBlockList blockList = mock(LazyBlockList.class);
    when(blockList.checksum()).thenReturn(checksum);
    when(blockList.getName()).thenReturn(blockListType);
    doAnswer(
            invocation -> {
              BiConsumer<byte[], Integer> consumer = invocation.getArgument(0);
              consumer.accept(bytes, bytes.length);
              return null;
            })
        .when(blockList)
        .consumeAll(any(BiConsumer.class));
    return blockList;
  }

  private static String generateChecksum(byte[] bytes) throws Exception {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    messageDigest.update(bytes, 0, bytes.length);
    return base16().lowerCase().encode(messageDigest.digest());
  }
}
