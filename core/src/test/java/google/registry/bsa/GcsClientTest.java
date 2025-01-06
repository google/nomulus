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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockLabel.LabelType;
import google.registry.bsa.api.BlockOrder;
import google.registry.bsa.api.BlockOrder.OrderType;
import google.registry.gcs.GcsUtils;
import java.io.ByteArrayInputStream;
import java.util.stream.Stream;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link GcsClient}. */
@ExtendWith(MockitoExtension.class)
class GcsClientTest {

  private GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  @Mock HttpsURLConnection connection;
  LazyBlockList lazyBlockList;
  GcsClient gcsClient;

  @BeforeEach
  void setup() throws Exception {
    gcsClient = new GcsClient(gcsUtils, "my-bucket", "SHA-256");
  }

  @Test
  void saveAndChecksumBlockList_success() throws Exception {
    String payload = "somedata\n";
    String payloadChecksum = "0737c8e591c68b93feccde50829aca86a80137547d8cfbe96bab6b20f8580c63";

    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream(("bsa-checksum\n" + payload).getBytes(UTF_8)));
    lazyBlockList = new LazyBlockList(BlockListType.BLOCK, connection);

    ImmutableMap<BlockListType, String> checksums =
        gcsClient.saveAndChecksumBlockList("some-name", ImmutableList.of(lazyBlockList));
    assertThat(gcsUtils.existsAndNotEmpty(BlobId.of("my-bucket", "some-name/BLOCK.csv"))).isTrue();
    assertThat(checksums).containsExactly(BlockListType.BLOCK, payloadChecksum);
    assertThat(gcsClient.readBlockList("some-name", BlockListType.BLOCK))
        .containsExactly("somedata");
  }

  @Test
  void readWrite_noData() throws Exception {
    gcsClient.writeOrderDiffs("job", Stream.of());
    assertThat(gcsClient.readOrderDiffs("job")).isEmpty();
  }

  @Test
  void readWriteOrderDiffs_success() throws Exception {
    ImmutableList<BlockOrder> orders =
        ImmutableList.of(
            BlockOrder.create(1, OrderType.CREATE), BlockOrder.create(2, OrderType.DELETE));
    gcsClient.writeOrderDiffs("job", orders.stream());
    assertThat(gcsClient.readOrderDiffs("job")).containsExactlyElementsIn(orders);
  }

  @Test
  void readWriteLabelDiffs_success() throws Exception {
    ImmutableList<BlockLabel> labels =
        ImmutableList.of(
            BlockLabel.create("1", LabelType.CREATE, ImmutableSet.of()),
            BlockLabel.create("2", LabelType.NEW_ORDER_ASSOCIATION, ImmutableSet.of("JA")),
            BlockLabel.create("3", LabelType.DELETE, ImmutableSet.of("JA", "EXTENDED_LATIN")));
    gcsClient.writeLabelDiffs("job", labels.stream());
    assertThat(gcsClient.readLabelDiffs("job")).containsExactlyElementsIn(labels);
  }
}
