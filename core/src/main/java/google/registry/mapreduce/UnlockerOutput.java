// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mapreduce;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.appengine.tools.mapreduce.Output;
import com.google.appengine.tools.mapreduce.OutputWriter;
import com.google.common.flogger.FluentLogger;
import google.registry.model.server.Lock;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/** An App Engine MapReduce "Output" that releases the given {@link Lock}. */
public class UnlockerOutput<O> extends Output<O, Lock> {

  private static final long serialVersionUID = 2884979908715512998L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Lock lock;

  public UnlockerOutput(Lock lock) {
    this.lock = lock;
  }

  private static class NoopWriter<O> extends OutputWriter<O> {

    private static final long serialVersionUID = -8327197554987150393L;

    @Override
    public void write(O object) {
      // Noop
    }

    @Override
    public boolean allowSliceRetry() {
      return true;
    }
  }

  @Override
  public List<NoopWriter<O>> createWriters(int numShards) {
    return Stream.generate(NoopWriter<O>::new).limit(numShards).collect(toImmutableList());
  }

  @Override
  public Lock finish(Collection<? extends OutputWriter<O>> writers) {
    logger.atInfo().log("Mapreduce finished; releasing lock '%s'.", lock);
    lock.release();
    return lock;
  }
}
