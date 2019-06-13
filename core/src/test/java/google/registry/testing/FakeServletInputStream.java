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

import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.ServletInputStream;

/**
 * Used to mock the return value of {@link javax.servlet.ServletRequest#getInputStream}.
 *
 * <p>Most servlets will call {@link javax.servlet.ServletRequest#getReader}, in which case you
 * can simply return a {@link java.io.StringReader} instance. But the getInputStream method is
 * not as simple to mock and requires an implementing class.
 */
public final class FakeServletInputStream extends ServletInputStream {

  private final InputStream input;

  public FakeServletInputStream(byte[] buf) {
    this.input = new ByteArrayInputStream(buf);
  }

  /**
   * Use a {@link ByteSource} as input for the servlet. Be sure to call {@link #close} after
   * your servlet runs so the resource opened via {@code bytes} gets closed.
   * @throws IOException
   */
  public FakeServletInputStream(ByteSource bytes) throws IOException {
    this.input = bytes.openStream();
  }

  @Override
  public int read() throws IOException {
    return input.read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return input.read(b, off, len);
  }

  @Override
  public void close() throws IOException {
    input.close();
  }
}
