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
import java.io.OutputStream;
import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RydeTarTest {

  @Test
  public void testWriteRead() throws Exception {
    byte[] expectedContent = "Testing 1, 2, 3".getBytes(UTF_8);
    String expectedFilename = "myFile.xml";
    DateTime expectedModified = DateTime.parse("2015-12-25T06:30:00.000Z");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream writer =
        RydeTar.openTarWriter(output, expectedContent.length, expectedFilename, expectedModified)) {
      writer.write(expectedContent);
    }
    byte[] tar = output.toByteArray();

    ByteArrayInputStream input = new ByteArrayInputStream(tar);
    try (RydeTar.TarInputStream reader = RydeTar.openTarReader(input)) {
      assertThat(reader.getFilename()).isEqualTo(expectedFilename);
      assertThat(reader.getModified()).isEqualTo(expectedModified);
      assertThat(ByteStreams.toByteArray(reader)).isEqualTo(expectedContent);
    }

  }
}
