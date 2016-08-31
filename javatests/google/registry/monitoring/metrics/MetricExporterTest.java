// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.metrics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Service.State;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link MetricExporter}. */
@RunWith(MockitoJUnitRunner.class)
public class MetricExporterTest {

  @Mock private MetricWriter writer;
  @Mock private MetricPoint<?> point;
  private MetricExporter exporter;
  private BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue;
  private final Optional<ImmutableList<MetricPoint<?>>> poisonPill = Optional.absent();
  private final Optional<ImmutableList<MetricPoint<?>>> emptyBatch =
      Optional.of(ImmutableList.<MetricPoint<?>>of());

  @Before
  public void setUp() throws Exception {
    writeQueue = new ArrayBlockingQueue<>(1);
    exporter = new MetricExporter(writeQueue, writer, Executors.defaultThreadFactory());
  }

  @Test
  public void testRun_takesFromQueue_whileRunning() throws Exception {
    exporter.startAsync().awaitRunning();

    insertAndAssert(emptyBatch);
    // Insert more batches to verify that the exporter hasn't gotten stuck
    insertAndAssert(emptyBatch);
    insertAndAssert(emptyBatch);

    assertThat(exporter.state()).isEqualTo(State.RUNNING);
  }

  @Test
  public void testRun_terminates_afterPoisonPill() throws Exception {
    exporter.startAsync().awaitRunning();

    insertAndAssert(poisonPill);
    try {
      exporter.awaitTerminated(500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      fail("MetricExporter did not reach the TERMINATED state after receiving a poison pill");
    }

    assertThat(exporter.state()).isEqualTo(State.TERMINATED);
  }

  @Test
  public void testRun_staysRunning_afterIOException() throws Exception {
    Optional<ImmutableList<MetricPoint<?>>> threeBatch =
        Optional.of(ImmutableList.of(point, point, point));
    doThrow(new IOException()).when(writer).write(Matchers.<MetricPoint<?>>any());
    exporter.startAsync();

    insertAndAssert(threeBatch);
    // Insert another batch in order to block until the exporter has processed the last one
    insertAndAssert(threeBatch);
    // Insert another to make sure the exporter hasn't gotten stuck
    insertAndAssert(threeBatch);

    assertThat(exporter.state()).isNotEqualTo(State.FAILED);
  }

  @Test
  public void testRun_writesMetrics() throws Exception {
    Optional<ImmutableList<MetricPoint<?>>> threeBatch =
        Optional.of(ImmutableList.of(point, point, point));
    exporter.startAsync();

    insertAndAssert(threeBatch);
    // Insert another batch in order to block until the exporter has processed the last one
    insertAndAssert(threeBatch);

    // Force the exporter to finish so that the verify counts below are deterministic
    insertAndAssert(poisonPill);
    try {
      exporter.awaitTerminated(500, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      fail("MetricExporter did not reach the TERMINATED state after receiving a poison pill");
    }

    assertThat(exporter.state()).isNotEqualTo(State.FAILED);
    verify(writer, times(6)).write(point);
    verify(writer, times(2)).flush();
  }

  /**
   * Helper method to insert into the {@link BlockingQueue} and assert that the item has been
   * enqueued.
   */
  private void insertAndAssert(Optional<ImmutableList<MetricPoint<?>>> batch) throws Exception {
    boolean isTaken = writeQueue.offer(batch, 500, TimeUnit.MILLISECONDS);
    assertThat(isTaken).isTrue();
  }
}
