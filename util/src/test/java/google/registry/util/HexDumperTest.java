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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HexDumper}. */
class HexDumperTest {

  @Test
  void testEmpty() {
    String input = "";
    String output = "[0 bytes total]\n";
    assertThat(input).isEmpty();
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8))).isEqualTo(output);
  }

  @Test
  void testOneLine() {
    String input = "hello world";
    String output =
        "[11 bytes total]\n"
            + "00000000  68 65 6c 6c  6f 20 77 6f  72 6c 64                  hello world     \n";
    assertThat(input).hasLength(11);
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8))).isEqualTo(output);
  }

  @Test
  void testMultiLine() {
    String input =
        ""
            + "\n"
            + "Maids heard the goblins cry:\n"
            + "\"Come buy our orchard fruits,\n"
            + "\"Come buy, come buy:\n";
    String output =
        "[81 bytes total]\n"
            + "00000000  0a 4d 61 69  64 73 20 68  65 61 72 64  20 74 68 65  .Maids heard the\n"
            + "00000016  20 67 6f 62  6c 69 6e 73  20 63 72 79  3a 0a 22 43   goblins cry:.\"C\n"
            + "00000032  6f 6d 65 20  62 75 79 20  6f 75 72 20  6f 72 63 68  ome buy our orch\n"
            + "00000048  61 72 64 20  66 72 75 69  74 73 2c 0a  22 43 6f 6d  ard fruits,.\"Com\n"
            + "00000064  65 20 62 75  79 2c 20 63  6f 6d 65 20  62 75 79 3a  e buy, come buy:\n"
            + "00000080  0a                                                  .               \n";
    assertThat(input).hasLength(81);
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8))).isEqualTo(output);
  }

  @Test
  void testFullLine() {
    String input = "hello worldddddd";
    String output =
        "[16 bytes total]\n"
            + "00000000  68 65 6c 6c  6f 20 77 6f  72 6c 64 64  64 64 64 64  hello worldddddd\n";
    assertThat(input).hasLength(16);
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8))).isEqualTo(output);
  }

  @Test
  void testUnicode() {
    String input = "(◕‿◕)";
    String output =
        "[11 bytes total]\n"
            + "00000000  28 e2 97 95  e2 80 bf e2  97 95 29                  (.........)     \n";
    assertThat(input).hasLength(5);
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8))).isEqualTo(output);
  }

  @Test
  void testRainbow() {
    byte[] input = new byte[256];
    for (int n = 0; n < 256; ++n) {
      input[n] = (byte) n;
    }
    String output =
        "[256 bytes total]\n"
            + "00000000  00 01 02 03  04 05 06 07  08 09 0a 0b  0c 0d 0e 0f  ................\n"
            + "00000016  10 11 12 13  14 15 16 17  18 19 1a 1b  1c 1d 1e 1f  ................\n"
            + "00000032  20 21 22 23  24 25 26 27  28 29 2a 2b  2c 2d 2e 2f   !\"#$%&'()*+,-./\n"
            + "00000048  30 31 32 33  34 35 36 37  38 39 3a 3b  3c 3d 3e 3f  0123456789:;<=>?\n"
            + "00000064  40 41 42 43  44 45 46 47  48 49 4a 4b  4c 4d 4e 4f  @ABCDEFGHIJKLMNO\n"
            + "00000080  50 51 52 53  54 55 56 57  58 59 5a 5b  5c 5d 5e 5f  PQRSTUVWXYZ[\\]^_\n"
            + "00000096  60 61 62 63  64 65 66 67  68 69 6a 6b  6c 6d 6e 6f  `abcdefghijklmno\n"
            + "00000112  70 71 72 73  74 75 76 77  78 79 7a 7b  7c 7d 7e 7f  pqrstuvwxyz{|}~.\n"
            + "00000128  80 81 82 83  84 85 86 87  88 89 8a 8b  8c 8d 8e 8f  ................\n"
            + "00000144  90 91 92 93  94 95 96 97  98 99 9a 9b  9c 9d 9e 9f  ................\n"
            + "00000160  a0 a1 a2 a3  a4 a5 a6 a7  a8 a9 aa ab  ac ad ae af  ................\n"
            + "00000176  b0 b1 b2 b3  b4 b5 b6 b7  b8 b9 ba bb  bc bd be bf  ................\n"
            + "00000192  c0 c1 c2 c3  c4 c5 c6 c7  c8 c9 ca cb  cc cd ce cf  ................\n"
            + "00000208  d0 d1 d2 d3  d4 d5 d6 d7  d8 d9 da db  dc dd de df  ................\n"
            + "00000224  e0 e1 e2 e3  e4 e5 e6 e7  e8 e9 ea eb  ec ed ee ef  ................\n"
            + "00000240  f0 f1 f2 f3  f4 f5 f6 f7  f8 f9 fa fb  fc fd fe ff  ................\n";
    assertThat(HexDumper.dumpHex(input)).isEqualTo(output);
  }

  @Test
  void testLineBuffering() throws Exception {
    // Assume that we have some data that's N bytes long.
    byte[] data = "Sweet to tongue and sound to eye; Come buy, come buy.".getBytes(UTF_8);
    // And a streaming HexDumper that displays N+1 characters per row.
    int perLine = data.length + 1;
    try (StringWriter out = new StringWriter();
        HexDumper dumper = new HexDumper(out, perLine, 0)) {
      // Constructing the object does not cause it to write anything to our upstream device.
      assertThat(out.toString()).isEmpty();
      // And then we write N bytes to the hex dumper.
      dumper.write(data);
      // But it won't output any hex because it's buffering a line of output internally.
      assertThat(out.toString()).isEmpty();
      // But one more byte will bring the total to N+1, thereby flushing a line of hexdump output.
      dumper.write(0);
      assertThat(out.toString())
          .isEqualTo(
              "00000000  53 77 65 65 74 20 74 6f 20 74 6f 6e 67 75 65 20 61 6e 64 20 73 6f "
                  + "75 6e 64 20 74 6f 20 65 79 65 3b 20 43 6f 6d 65 20 62 75 79 2c 20 63 6f 6d 65 "
                  + "20 62 75 79 2e 00  Sweet to tongue and sound to eye; Come buy, come buy..\n");
      // No additional data will need to be written upon close.
      int oldTotal = out.toString().length();
      assertThat(out.toString().length()).isEqualTo(oldTotal);
    }
  }

  @Test
  void testFlush() throws Exception {
    try (StringWriter out = new StringWriter();
        HexDumper dumper = new HexDumper(out)) {
      dumper.write("hello ".getBytes(UTF_8));
      assertThat(out.toString()).isEmpty();
      dumper.flush();
      assertThat(out.toString()).isEqualTo("00000000  68 65 6c 6c  6f 20 ");
      dumper.write("world".getBytes(UTF_8));
      assertThat(out.toString()).isEqualTo("00000000  68 65 6c 6c  6f 20 ");
      dumper.flush();
      assertThat(out.toString()).isEqualTo("00000000  68 65 6c 6c  6f 20 77 6f  72 6c 64 ");
      dumper.close();
      assertThat(out.toString())
          .isEqualTo(
              "00000000  68 65 6c 6c  6f 20 77 6f  72 6c 64                  hello world     \n");
    }
  }

  @Test
  void testPerLineIsOne() {
    String input = "hello";
    String output =
        "[5 bytes total]\n"
            + "00000000  68  h\n"
            + "00000001  65  e\n"
            + "00000002  6c  l\n"
            + "00000003  6c  l\n"
            + "00000004  6f  o\n";
    assertThat(HexDumper.dumpHex(input.getBytes(UTF_8), 1, 0)).isEqualTo(output);
  }

  @Test
  void testBadArgumentPerLineZero() {
    HexDumper.dumpHex(new byte[1], 1, 0);
    assertThrows(IllegalArgumentException.class, () -> HexDumper.dumpHex(new byte[1], 0, 0));
  }

  @Test
  void testBadArgumentPerLineNegative() {
    HexDumper.dumpHex(new byte[1], 1, 0);
    assertThrows(IllegalArgumentException.class, () -> HexDumper.dumpHex(new byte[1], -1, 0));
  }

  @Test
  void testBadArgumentPerGroupNegative() {
    HexDumper.dumpHex(new byte[1], 1, 0);
    assertThrows(IllegalArgumentException.class, () -> HexDumper.dumpHex(new byte[1], 1, -1));
  }

  @Test
  void testBadArgumentPerGroupGreaterThanOrEqualToPerLine() {
    HexDumper.dumpHex(new byte[1], 1, 0);
    HexDumper.dumpHex(new byte[1], 2, 1);
    assertThrows(IllegalArgumentException.class, () -> HexDumper.dumpHex(new byte[1], 1, 1));
  }

  @Test
  void testBadArgumentBytesIsNull() {
    HexDumper.dumpHex(new byte[1]);
    assertThrows(NullPointerException.class, () -> HexDumper.dumpHex(null));
  }

  @Test
  void testMultiClose() throws Exception {
    try (StringWriter out = new StringWriter();
        HexDumper dumper = new HexDumper(out)) {
      dumper.close();
      dumper.close();
      out.close();
    }
  }
}
