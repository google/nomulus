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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import javax.annotation.Nonnull;
import javax.servlet.ServletOutputStream;

/**
 * Used to mock the return value of {@link javax.servlet.ServletResponse#getOutputStream}.
 *
 * <p>Most servlets will call {@link javax.servlet.ServletResponse#getWriter}, in which case you
 * can simply return a {@link java.io.StringWriter} instance. But the getOutputStream method is
 * not as simple to mock and requires an implementing class.
 */
public final class FakeServletOutputStream extends ServletOutputStream {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  /** @see java.io.OutputStream#write(int) */
  @Override
  public void write(int b) {
    out.write(b);
  }

  /** @see java.io.OutputStream#write(byte[]) */
  @Override
  public void write(@Nonnull @SuppressWarnings("null") byte[] b) throws IOException {
    out.write(b);
  }

  /** @see java.io.OutputStream#write(byte[], int, int) */
  @Override
  public void write(@Nonnull @SuppressWarnings("null") byte[] b, int off, int len) {
    out.write(b, off, len);
  }

  /** Converts contents to a string, assuming UTF-8 encoding. */
  @Override
  public String toString() {
    try {
      return out.toString(UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }
}
