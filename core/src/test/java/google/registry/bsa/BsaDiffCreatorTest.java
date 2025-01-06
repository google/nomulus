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
import static google.registry.bsa.BsaDiffCreator.ORDER_ID_SENTINEL;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.BsaDiffCreator.BsaDiff;
import google.registry.bsa.BsaDiffCreator.Canonicals;
import google.registry.bsa.BsaDiffCreator.LabelOrderPair;
import google.registry.bsa.BsaDiffCreator.Line;
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockLabel.LabelType;
import google.registry.bsa.api.BlockOrder;
import google.registry.bsa.api.BlockOrder.OrderType;
import google.registry.bsa.persistence.DownloadSchedule;
import google.registry.bsa.persistence.DownloadSchedule.CompletedJob;
import google.registry.tldconfig.idn.IdnTableEnum;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaDiffCreator}. */
@ExtendWith(MockitoExtension.class)
class BsaDiffCreatorTest {

  @Mock GcsClient gcsClient;

  @Mock
  DownloadSchedule schedule;

  @Mock
  CompletedJob completedJob;

  @Mock IdnChecker idnChecker;

  BsaDiffCreator diffCreator;

  @Test
  void firstDiff() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.empty());
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(
            BlockLabel.create("test1", LabelType.CREATE, ImmutableSet.of("JA")),
            BlockLabel.create("test2", LabelType.CREATE, ImmutableSet.of("JA")),
            BlockLabel.create("test3", LabelType.CREATE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders())
        .containsExactly(
            BlockOrder.create(1, OrderType.CREATE),
            BlockOrder.create(2, OrderType.CREATE),
            BlockOrder.create(3, OrderType.CREATE),
            BlockOrder.create(4, OrderType.CREATE));
  }

  @Test
  void firstDiff_labelMultipleOccurrences() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK_PLUS))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,5"));
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.empty());
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(
            BlockLabel.create("test1", LabelType.CREATE, ImmutableSet.of("JA")),
            BlockLabel.create("test2", LabelType.CREATE, ImmutableSet.of("JA")),
            BlockLabel.create("test3", LabelType.CREATE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders())
        .containsExactly(
            BlockOrder.create(1, OrderType.CREATE),
            BlockOrder.create(2, OrderType.CREATE),
            BlockOrder.create(3, OrderType.CREATE),
            BlockOrder.create(4, OrderType.CREATE),
            BlockOrder.create(5, OrderType.CREATE));
  }

  @Test
  void unchanged() {
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels()).isEmpty();
    assertThat(diff.getOrders()).isEmpty();
  }

  @Test
  void allRemoved() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK)).thenReturn(Stream.of());
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(
            BlockLabel.create("test1", LabelType.DELETE, ImmutableSet.of("JA")),
            BlockLabel.create("test2", LabelType.DELETE, ImmutableSet.of("JA")),
            BlockLabel.create("test3", LabelType.DELETE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders())
        .containsExactly(
            BlockOrder.create(1, OrderType.DELETE),
            BlockOrder.create(2, OrderType.DELETE),
            BlockOrder.create(3, OrderType.DELETE),
            BlockOrder.create(4, OrderType.DELETE));
  }

  @Test
  void existingLabelNewOrder() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2;5", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(
            BlockLabel.create("test1", LabelType.NEW_ORDER_ASSOCIATION, ImmutableSet.of("JA")));
    assertThat(diff.getOrders()).containsExactly(BlockOrder.create(5, OrderType.CREATE));
  }

  @Test
  void newLabelNewOrder() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(
            Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4", "test4,5"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(BlockLabel.create("test4", LabelType.CREATE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders()).containsExactly(BlockOrder.create(5, OrderType.CREATE));
  }

  @Test
  void removeOrderOnly() {
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels()).isEmpty();
    assertThat(diff.getOrders()).containsExactly(BlockOrder.create(4, OrderType.DELETE));
  }

  @Test
  void removeOrderOnly_multiLabelOrder() {
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,2", "test2,3", "test3,4"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels()).isEmpty();
    assertThat(diff.getOrders()).containsExactly(BlockOrder.create(1, OrderType.DELETE));
  }

  @Test
  void removeLabelAndOrder() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test3,1;4"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(BlockLabel.create("test2", LabelType.DELETE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders()).containsExactly(BlockOrder.create(3, OrderType.DELETE));
  }

  @Test
  void removeLabelAndOrder_multi() {
    when(idnChecker.getAllValidIdns(anyString())).thenReturn(ImmutableSet.of(IdnTableEnum.JA));
    when(gcsClient.readBlockList("first", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test1,1;2", "test2,3", "test3,1;4"));
    when(gcsClient.readBlockList("second", BlockListType.BLOCK))
        .thenReturn(Stream.of("domainLabel,orderIDs", "test2,3"));
    when(gcsClient.readBlockList(anyString(), eq(BlockListType.BLOCK_PLUS)))
        .thenAnswer((ignore) -> Stream.of());
    diffCreator = new BsaDiffCreator(gcsClient);
    when(schedule.jobName()).thenReturn("second");
    when(completedJob.jobName()).thenReturn("first");
    when(schedule.latestCompleted()).thenReturn(Optional.of(completedJob));
    BsaDiff diff = diffCreator.createDiff(schedule, idnChecker);
    assertThat(diff.getLabels())
        .containsExactly(
            BlockLabel.create("test1", LabelType.DELETE, ImmutableSet.of("JA")),
            BlockLabel.create("test3", LabelType.DELETE, ImmutableSet.of("JA")));
    assertThat(diff.getOrders())
        .containsExactly(
            BlockOrder.create(1, OrderType.DELETE),
            BlockOrder.create(2, OrderType.DELETE),
            BlockOrder.create(4, OrderType.DELETE));
  }

  @Test
  void parseLine_singleOrder() {
    Line line = BsaDiffCreator.parseLine("testmark4,3008916894861");
    assertThat(line.label()).isEqualTo("testmark4");
    assertThat(line.orderIds()).containsExactly(3008916894861L);
  }

  @Test
  void parseLine_multiOrder() {
    Line line = BsaDiffCreator.parseLine("xn--thnew-yorkinquirer-fxb,6927233432961;9314162579861");
    assertThat(line.label()).isEqualTo("xn--thnew-yorkinquirer-fxb");
    assertThat(line.orderIds()).containsExactly(6927233432961L, 9314162579861L);
  }

  @Test
  void parseLine_invalidOrder() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> BsaDiffCreator.parseLine("testmark4," + ORDER_ID_SENTINEL)))
        .hasMessageThat()
        .contains("Invalid order id");
  }

  @Test
  void line_labelOrderPairs() {
    Line line = Line.of("a", ImmutableList.of(1L, 2L, 3L));
    assertThat(line.labelOrderPairs(new Canonicals<>()))
        .containsExactly(
            LabelOrderPair.of("a", 1L), LabelOrderPair.of("a", 2L), LabelOrderPair.of("a", 3L));
  }

  @Test
  void canonicals_get() {
    Canonicals<Long> canonicals = new Canonicals<>();
    Long cvalue = canonicals.get(1L);
    assertThat(canonicals.get(1L)).isSameInstanceAs(cvalue);
  }
}
