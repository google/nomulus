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

package google.registry.rde;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpProgressMonitor;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** A progress monitor for SFTP operations that writes status to logs periodically. */
public class LoggingSftpProgressMonitor implements SftpProgressMonitor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long LOGGING_CHUNK_SIZE_BYTES = 5 * 1024 * 1024;

  private final Clock clock;
  private long bytesOfLastLog = 0;
  private int callsSinceLastLog = 0;
  private DateTime timeOfLastLog;

  @Inject
  LoggingSftpProgressMonitor(Clock clock) {
    this.clock = clock;
  }

  /** Nice display values for SFTP operation modes. */
  private static final ImmutableMap<Integer, String> OPERATION_MODES =
      new ImmutableMap.Builder<Integer, String>()
          .put(ChannelSftp.OVERWRITE, "OVERWRITE")
          .put(ChannelSftp.RESUME, "RESUME")
          .put(ChannelSftp.APPEND, "APPEND")
          .build();

  @Override
  public void init(int op, String src, String dest, long max) {
    timeOfLastLog = clock.nowUtc();
    logger.atInfo().log(
        "Initiating SFTP transfer from '%s' to '%s' using mode %s, max size %,d bytes.",
        src, dest, OPERATION_MODES.getOrDefault(op, "(unknown)"), max);
  }

  @Override
  public boolean count(long count) {
    callsSinceLastLog++;
    long bytesSinceLastLog = count - bytesOfLastLog;
    if (bytesSinceLastLog > LOGGING_CHUNK_SIZE_BYTES) {
      DateTime now = clock.nowUtc();
      logger.atInfo().log(
          "%,d more bytes transmitted in %,d ms; %,d bytes in total. [%,d calls to count()]",
          bytesSinceLastLog,
          new Duration(timeOfLastLog, now).getMillis(),
          count,
          callsSinceLastLog);
      bytesOfLastLog = count;
      callsSinceLastLog = 0;
      timeOfLastLog = now;
    }
    // True means that the upload continues.
    return true;
  }

  @Override
  public void end() {
    logger.atInfo().log("SFTP operation finished.");
  }
}
