// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.base.Joiner;
import google.registry.bsa.persistence.BsaTestingUtils;
import google.registry.gcs.GcsUtils;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaValidateAction}. */
@ExtendWith(MockitoExtension.class)
public class BsaValidateActionTest {

  private static final String DOWNLOAD_JOB_NAME = "job";

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Mock BsaLock bsaLock;

  @Mock Response response;

  private GcsClient gcsClient;

  private BsaValidateAction action;

  @BeforeEach
  void setup() {
    gcsClient =
        new GcsClient(new GcsUtils(LocalStorageHelper.getOptions()), "my-bucket", "SHA-256");
    action = new BsaValidateAction(gcsClient, bsaLock, response);
  }

  static void createBlockList(GcsClient gcsClient, BlockListType blockListType, String content)
      throws Exception {
    BlobId blobId =
        gcsClient.getBlobId(DOWNLOAD_JOB_NAME, GcsClient.getBlockListFileName(blockListType));
    try (var writer = gcsClient.getWriter(blobId)) {
      writer.write(content);
    }
  }

  @Test
  void fetchDownloadedLabels_success() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent =
        """
        domainLabel,orderIDs
        test2,4
        test3,5
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    assertThat(action.fetchDownloadedLabels(DOWNLOAD_JOB_NAME))
        .containsExactly("test1", "test2", "test3");
  }

  @Test
  void fetchPersistedLabels_multipleOfBatchSize_success() {
    BsaTestingUtils.persistBsaLabel("a");
    BsaTestingUtils.persistBsaLabel("b");
    BsaTestingUtils.persistBsaLabel("c");

    assertThat(action.fetchPersistedLabels(1)).containsExactly("a", "b", "c");
  }

  @Test
  void fetchPersistedLabels_notMultipleOfBatchSize_success() {
    BsaTestingUtils.persistBsaLabel("a");
    BsaTestingUtils.persistBsaLabel("b");
    BsaTestingUtils.persistBsaLabel("c");

    assertThat(action.fetchPersistedLabels(2)).containsExactly("a", "b", "c");
  }

  @Test
  void checkBsaLabels_noErrors() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent =
        """
        domainLabel,orderIDs
        test2,4
        test3,5
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    BsaTestingUtils.persistBsaLabel("test1");
    BsaTestingUtils.persistBsaLabel("test2");
    BsaTestingUtils.persistBsaLabel("test3");

    assertThat(action.checkBsaLabels(DOWNLOAD_JOB_NAME)).isEmpty();
  }

  @Test
  void checkBsaLabels_withErrors() throws Exception {
    String blockContent =
        """
        domainLabel,orderIDs
        test1,1;2
        test2,3
        """;
    String blockPlusContent = """
        domainLabel,orderIDs
        test2,4
        """;
    createBlockList(gcsClient, BlockListType.BLOCK, blockContent);
    createBlockList(gcsClient, BlockListType.BLOCK_PLUS, blockPlusContent);
    BsaTestingUtils.persistBsaLabel("test2");
    BsaTestingUtils.persistBsaLabel("test3");

    String allErrors = Joiner.on('\n').join(action.checkBsaLabels(DOWNLOAD_JOB_NAME));

    assertThat(allErrors).contains("Found 1 missing labels in the DB. Examples: [test1]");
    assertThat(allErrors).contains("Found 1 unexpected labels in the DB. Examples: [test3]");
  }
}
