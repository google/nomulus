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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service to asynchronously push bundles of {@link MetricPoint} instances to a {@link
 * MetricWriter}.
 */
class MetricExporter extends AbstractExecutionThreadService {

  private static final Logger logger = Logger.getLogger(MetricReporter.class.getName());

  private final BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue;
  private final MetricWriter writer;
  private final ThreadFactory threadFactory;

  MetricExporter(
      BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue,
      MetricWriter writer,
      ThreadFactory threadFactory) {
    this.writeQueue = writeQueue;
    this.writer = writer;
    this.threadFactory = threadFactory;
  }

  @Override
  protected void run() throws Exception {
    logger.info("Started up MetricExporter");
    while (isRunning()) {
      Optional<ImmutableList<MetricPoint<?>>> batch = writeQueue.take();
      logger.info("Got a batch of points from the writeQueue");
      if (batch.isPresent()) {
        logger.info("Batch contains data, writing to MetricWriter");
        try {
          for (MetricPoint<?> point : batch.get()) {
            writer.write(point);
          }
          writer.flush();
        } catch (IOException exception) {
          logger.log(
              Level.SEVERE, "Threw an exception while writing or flushing metrics", exception);
        }
      } else {
        logger.info("Received a poison pill, stopping now");
        // An absent optional indicates that the Reporter wants this service to shut down.
        return;
      }
    }
  }

  @Override
  protected Executor executor() {
    final ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
    // Make sure the ExecutorService terminates when this service does.
    addListener(
        new Listener() {
          @Override
          public void terminated(State from) {
            executor.shutdown();
          }

          @Override
          public void failed(State from, Throwable failure) {
            executor.shutdown();
          }
        },
        directExecutor());
    return executor;
  }
}
