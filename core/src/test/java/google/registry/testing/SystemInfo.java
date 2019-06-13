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

package google.registry.testing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.annotation.concurrent.ThreadSafe;

/** Utility class for getting system information in tests. */
@ThreadSafe
public final class SystemInfo {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final LoadingCache<String, Boolean> hasCommandCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<String, Boolean>() {
                @Override
                public Boolean load(String cmd) throws InterruptedException {
                  try {
                    Process pid = Runtime.getRuntime().exec(cmd);
                    pid.getOutputStream().close();
                    pid.waitFor();
                  } catch (IOException e) {
                    logger.atWarning().withCause(e).log("%s command not available", cmd);
                    return false;
                  }
                  return true;
                }
              });

  /**
   * Returns {@code true} if system command can be run from path.
   *
   * <p><b>Warning:</b> The command is actually run! So there could be side-effects. You might
   * need to specify a version flag or something. Return code is ignored.
   *
   * <p>This result is a memoized. If multiple therads try to get the same result at once, the
   * heavy lifting will only be performed by the first thread and the rest will wait.
   *
   * @throws ExecutionException
   */
  public static boolean hasCommand(String cmd) throws ExecutionException {
    return hasCommandCache.get(cmd);
  }

}
