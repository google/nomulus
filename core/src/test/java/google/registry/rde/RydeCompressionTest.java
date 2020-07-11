// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RydeCompression} */
final class RydeCompressionTest {

  @Test
  void testCompression_decompression() throws Exception {
    byte[] expected = "Testing 1, 2, 3".getBytes(UTF_8);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream compressor = RydeCompression.openCompressor(output)) {
      compressor.write(expected);
    }
    byte[] compressed = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(compressed);
    try (InputStream decompressor = RydeCompression.openDecompressor(input)) {
      assertThat(ByteStreams.toByteArray(decompressor)).isEqualTo(expected);
    }
  }
}
