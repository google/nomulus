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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.WillNotClose;

/**
 * {@link OutputStream} delegate that writes simultaneously to multiple other output streams.
  */
public final class TeeOutputStream extends OutputStream {

  private final ImmutableList<? extends OutputStream> outputs;
  private boolean isClosed;

  public TeeOutputStream(@WillNotClose Iterable<? extends OutputStream> outputs) {
    this.outputs = ImmutableList.copyOf(outputs);
    checkArgument(!this.outputs.isEmpty(), "must provide at least one output stream");
  }

  /** @see java.io.OutputStream#write(int) */
  @Override
  public void write(int b) throws IOException {
    checkState(!isClosed, "outputstream closed");
    for (OutputStream out : outputs) {
      out.write(b);
    }
  }

  /** @see #write(byte[], int, int) */
  @Override
  public void write(byte[] b) throws IOException {
    this.write(b, 0, b.length);
  }

  /** @see java.io.OutputStream#write(byte[], int, int) */
  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    checkState(!isClosed, "outputstream closed");
    for (OutputStream out : outputs) {
      out.write(b, off, len);
    }
  }

  /** Closes the stream.  Any calls to a {@code write()} method after this will throw. */
  @Override
  public void close() {
    isClosed = true;
  }
}
