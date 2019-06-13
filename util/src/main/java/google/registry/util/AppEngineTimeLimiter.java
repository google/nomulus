// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.appengine.api.ThreadManager.currentRequestThreadFactory;

import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A factory for {@link TimeLimiter} instances that use request threads, which carry the namespace
 * and live only as long as the request that spawned them.
 *
 * <p>It is safe to reuse instances of this class, but there is no benefit in doing so over creating
 * a fresh instance each time.
 */
public class AppEngineTimeLimiter {

  /**
   * An {@code ExecutorService} that uses a new thread for every task.
   *
   * <p>We need to use fresh threads for each request so that we can use App Engine's request
   * threads.  If we cached these threads in a thread pool (and if we were executing on a backend,
   * where there is no time limit on requests) the caching would cause the thread to keep the task
   * that opened it alive even after returning an http response, and would also cause the namespace
   * that the original thread was created in to leak out to later reuses of the thread.
   *
   * <p>Since there are no cached resources, this class doesn't have to support being shutdown.
   */
  private static class NewRequestThreadExecutorService extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
      currentRequestThreadFactory().newThread(command).start();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public void shutdown() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  public static TimeLimiter create() {
    return SimpleTimeLimiter.create(new NewRequestThreadExecutorService());
  }
}
