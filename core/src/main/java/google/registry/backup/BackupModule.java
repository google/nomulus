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

package google.registry.backup;

import static com.google.appengine.api.ThreadManager.currentRequestThreadFactory;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListeningExecutorService;
import dagger.Module;
import dagger.Provides;
import google.registry.cron.CommitLogFanoutAction;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import java.lang.annotation.Documented;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Qualifier;
import javax.servlet.http.HttpServletRequest;

/**
 * Dagger module for backup package.
 *
 * @see "google.registry.module.backend.BackendComponent"
 */
@Module
public final class BackupModule {

  /** Dagger qualifier for backups. */
  @Qualifier
  @Documented
  public @interface Backups {}

  /** Number of threads in the threaded executor. */
  private static final int NUM_THREADS = 10;

  @Provides
  @Parameter("bucket")
  static int provideBucket(HttpServletRequest req) {
    String param = extractRequiredParameter(req, CommitLogFanoutAction.BUCKET_PARAM);
    Integer bucket = Ints.tryParse(param);
    if (bucket == null) {
      throw new BadRequestException("Bad bucket id");
    }
    return bucket;
  }

  @Provides
  @Backups
  static ListeningExecutorService provideListeningExecutorService() {
    return listeningDecorator(newFixedThreadPool(NUM_THREADS, currentRequestThreadFactory()));
  }

  @Provides
  static ScheduledExecutorService provideScheduledExecutorService() {
    return Executors.newSingleThreadScheduledExecutor();
  }
}
