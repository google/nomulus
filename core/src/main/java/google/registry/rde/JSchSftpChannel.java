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

import static com.google.common.base.Preconditions.checkNotNull;

import com.jcraft.jsch.ChannelSftp;
import java.io.Closeable;

/**
 * {@link ChannelSftp} wrapper that implements {@link Closeable}.
 *
 * <p>This class acts as syntactic sugar for JSch so we can open and close SFTP connections in a
 * way that's friendlier to Java 7 try-resource statements.
 *
 * @see JSchSshSession#openSftpChannel()
 */
final class JSchSftpChannel implements Closeable {
  private final ChannelSftp channel;

  JSchSftpChannel(ChannelSftp channel) {
    this.channel = checkNotNull(channel, "channel");
  }

  /** Returns {@link ChannelSftp} instance wrapped by this object. */
  public ChannelSftp get() {
    return channel;
  }

  @Override
  public void close() {
    channel.disconnect();
  }
}
