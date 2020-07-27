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

package google.registry.rde;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredDatetimeParameter;
import static google.registry.request.RequestParameters.extractSetOfDatetimeParameters;
import static google.registry.request.RequestParameters.extractSetOfParameters;

import com.google.appengine.api.taskqueue.Queue;
import com.google.common.collect.ImmutableSet;
import com.jcraft.jsch.SftpProgressMonitor;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/**
 * Dagger module for RDE package.
 *
 * @see "google.registry.module.backend.BackendRequestComponent"
 */
@Module
public abstract class RdeModule {

  public static final String PARAM_WATERMARK = "watermark";
  public static final String PARAM_WATERMARKS = "watermarks";
  public static final String PARAM_MANUAL = "manual";
  public static final String PARAM_DIRECTORY = "directory";
  public static final String PARAM_MODE = "mode";
  public static final String PARAM_REVISION = "revision";
  public static final String PARAM_LENIENT = "lenient";

  @Provides
  @Parameter(PARAM_WATERMARK)
  static DateTime provideWatermark(HttpServletRequest req) {
    return extractRequiredDatetimeParameter(req, PARAM_WATERMARK);
  }

  @Provides
  @Parameter(PARAM_WATERMARKS)
  static ImmutableSet<DateTime> provideWatermarks(HttpServletRequest req) {
    return extractSetOfDatetimeParameters(req, PARAM_WATERMARKS);
  }

  @Provides
  @Parameter(PARAM_MANUAL)
  static boolean provideManual(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_MANUAL);
  }

  @Provides
  @Parameter(PARAM_DIRECTORY)
  static Optional<String> provideDirectory(HttpServletRequest req) {
    return extractOptionalParameter(req, PARAM_DIRECTORY);
  }

  @Provides
  @Parameter(PARAM_MODE)
  static ImmutableSet<String> provideMode(HttpServletRequest req) {
    return extractSetOfParameters(req, PARAM_MODE);
  }

  @Provides
  @Parameter(PARAM_REVISION)
  static Optional<Integer> provideRevision(HttpServletRequest req) {
    return extractOptionalIntParameter(req, PARAM_REVISION);
  }

  @Provides
  @Parameter(PARAM_LENIENT)
  static boolean provideLenient(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_LENIENT);
  }

  @Provides
  @Named("brda")
  static Queue provideQueueBrda() {
    return getQueue("brda");
  }

  @Provides
  @Named("rde-report")
  static Queue provideQueueRdeReport() {
    return getQueue("rde-report");
  }

  @Binds
  abstract SftpProgressMonitor provideSftpProgressMonitor(
      LoggingSftpProgressMonitor loggingSftpProgressMonitor);

  private RdeModule() {}
}
