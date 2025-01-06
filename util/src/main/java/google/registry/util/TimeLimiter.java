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

package google.registry.util;

import static google.registry.util.GcpJsonFormatter.getCurrentTraceId;
import static google.registry.util.GcpJsonFormatter.setCurrentTraceId;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class TimeLimiter {

  private static class NewRequestThreadExecutorService extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
      MoreExecutors.platformThreadFactory().newThread(new LogTracingRunnable(command)).start();
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

  private static class LogTracingRunnable implements Runnable {

    private final Runnable runnable;
    @Nullable private final String logTraceId;

    LogTracingRunnable(Runnable runnable) {
      this.runnable = runnable;
      logTraceId = getCurrentTraceId();
    }

    @Override
    public void run() {
      setCurrentTraceId(logTraceId);
      try {
        this.runnable.run();
      } finally {
        setCurrentTraceId(null);
      }
    }
  }

  public static com.google.common.util.concurrent.TimeLimiter create() {
    return SimpleTimeLimiter.create(new NewRequestThreadExecutorService());
  }
}
