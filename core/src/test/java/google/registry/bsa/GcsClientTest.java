// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.truth.Truth8.assertThat;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.BlockListFetcher.LazyBlockList;
import google.registry.bsa.api.Label;
import google.registry.bsa.api.Label.LabelType;
import google.registry.bsa.api.Order;
import google.registry.bsa.api.Order.OrderType;
import google.registry.gcs.GcsUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    lazyBlockList = new LazyBlockList(BlockList.BLOCK, connection);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("somedata\n".getBytes(StandardCharsets.UTF_8)));

    ImmutableMap<BlockList, String> checksums =
        gcsClient.saveAndChecksumBlockList("some-name", ImmutableList.of(lazyBlockList));
    assertThat(gcsUtils.existsAndNotEmpty(BlobId.of("my-bucket", "some-name/BLOCK.csv"))).isTrue();
    assertThat(checksums)
        .containsExactly(
            BlockList.BLOCK, "0737c8e591c68b93feccde50829aca86a80137547d8cfbe96bab6b20f8580c63");
    assertThat(gcsClient.readBlockList("some-name", BlockList.BLOCK)).containsExactly("somedata");
  }

  @Test
  void readWrite_noData() throws Exception {
    gcsClient.writeOrderDiffs("job", Stream.of());
    assertThat(gcsClient.readOrderDiffs("job")).isEmpty();
  }

  @Test
  void readWriteOrderDiffs_success() throws Exception {
    ImmutableList<Order> orders =
        ImmutableList.of(Order.of(1, OrderType.CREATE), Order.of(2, OrderType.DELETE));
    gcsClient.writeOrderDiffs("job", orders.stream());
    assertThat(gcsClient.readOrderDiffs("job")).containsExactlyElementsIn(orders);
  }

  @Test
  void readWriteLabelDiffs_success() throws Exception {
    ImmutableList<Label> labels =
        ImmutableList.of(
            Label.of("1", LabelType.CREATE.CREATE, ImmutableSet.of()),
            Label.of("2", LabelType.NEW_ORDER_ASSOCIATION, ImmutableSet.of("JA")),
            Label.of("3", LabelType.DELETE, ImmutableSet.of("JA", "EXTENDED_LATIN")));
    gcsClient.writeLabelDiffs("job", labels.stream());
    assertThat(gcsClient.readLabelDiffs("job")).containsExactlyElementsIn(labels);
  }
}
