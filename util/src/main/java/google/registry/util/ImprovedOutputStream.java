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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.flogger.FluentLogger;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import javax.annotation.WillCloseWhenClosed;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * {@link OutputStream} wrapper that offers some additional magic.
 *
 * <ul>
 * <li>Byte counting
 * <li>Always {@link #flush()} on {@link #close()}
 * <li>Check expected byte count when closed (Optional)
 * <li>Close original {@link OutputStream} when closed (Optional)
 * <li>Overridable {@link #onClose()} method
 * <li>Throws {@link NullPointerException} if written after {@link #close()}
 * </ul>
 *
 * @see ImprovedInputStream
 * @see com.google.common.io.CountingOutputStream
 */
@NotThreadSafe
public class ImprovedOutputStream extends FilterOutputStream {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private long count;
  private final boolean shouldClose;
  private final String name;

  public ImprovedOutputStream(String name, @WillCloseWhenClosed OutputStream out) {
    this(name, out, true);
  }

  public ImprovedOutputStream(String name, OutputStream out, boolean shouldClose) {
    super(checkNotNull(out, "out"));
    this.shouldClose = shouldClose;
    this.name = name;
  }

  /** Returns the number of bytes that have been written to this stream thus far. */
  public long getBytesWritten() {
    return count;
  }

  /** @see java.io.FilterOutputStream#write(int) */
  @Override
  @OverridingMethodsMustInvokeSuper
  public void write(int b) throws IOException {
    out.write(b);
    ++count;
  }

  /** @see #write(byte[], int, int) */
  @Override
  public final void write(byte[] b) throws IOException {
    this.write(b, 0, b.length);
  }

  /** @see java.io.FilterOutputStream#write(byte[], int, int) */
  @Override
  @OverridingMethodsMustInvokeSuper
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    count += len;
  }

  /**
   * Flushes, logs byte count, checks byte count (optional), closes (optional), and self-sabotages.
   *
   * <p>This method may not be overridden, use {@link #onClose()} instead.
   *
   * @see java.io.OutputStream#close()
   */
  @Override
  @NonFinalForTesting
  public void close() throws IOException {
    if (out == null) {
      return;
    }
    try {
      flush();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("flush() failed for %s", name);
    }
    onClose();
    if (shouldClose) {
      out.close();
    }
    out = null;
    logger.atInfo().log("%s closed with %,d bytes written", name, count);
  }

  /**
   * Overridable method that's called by {@link #close()}.
   *
   * <p>This method does nothing by default.
   */
  protected void onClose() throws IOException {
    // Does nothing by default.
  }
}
