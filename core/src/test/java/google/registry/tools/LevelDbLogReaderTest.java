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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.tools.LevelDbUtil.MAX_RECORD;
import static google.registry.tools.LevelDbUtil.addRecord;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import google.registry.tools.LevelDbLogReader.ChunkType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link LevelDbLogReader}. */
final class LevelDbLogReaderTest {

  // Size of the test record.  Any value < 256 will do.
  private static final int TEST_RECORD_SIZE = 231;

  // The maximum offset at which a test record can be inserted, measured in bytes from the beginning
  // of the block.
  private static final int MAX_TEST_RECORD_OFFSET =
      LevelDbLogReader.BLOCK_SIZE - (LevelDbLogReader.HEADER_SIZE + TEST_RECORD_SIZE);

  private TestBlock makeBlockOfRepeatingBytes(int startVal) {
    byte[] block = new byte[LevelDbLogReader.BLOCK_SIZE];
    int pos = 0;
    int recordCount = 0;
    while (pos < MAX_TEST_RECORD_OFFSET) {
      pos = addRecord(block, pos, ChunkType.FULL, TEST_RECORD_SIZE, 0xffff & (pos + startVal));
      ++recordCount;
    }
    return new TestBlock(block, recordCount);
  }

  @Test
  void testSimpleBlock() throws IOException {
    TestBlock block = makeBlockOfRepeatingBytes(0);
    assertThat(readIncrementally(block.data)).hasSize(block.recordCount);
  }

  @Test
  void testLargeRecord() throws IOException {
    byte[] block0 = new byte[LevelDbLogReader.BLOCK_SIZE];
    addRecord(block0, 0, ChunkType.FIRST, MAX_RECORD, (byte) 1);
    assertThat(readIncrementally(block0)).isEmpty();

    byte[] block1 = new byte[LevelDbLogReader.BLOCK_SIZE];
    addRecord(block1, 0, ChunkType.MIDDLE, MAX_RECORD, (byte) 2);
    assertThat(readIncrementally(block0, block1)).isEmpty();

    byte[] block2 = new byte[LevelDbLogReader.BLOCK_SIZE];
    addRecord(block2, 0, ChunkType.LAST, MAX_RECORD, (byte) 3);

    List<byte[]> records = readIncrementally(block0, block1, block2);
    assertThat(records).hasSize(1);
    byte[] record = records.get(0);

    for (int i = 0; i < MAX_RECORD; ++i) {
      assertThat(record[i]).isEqualTo((i % 2 == 1) ? 0 : 1);
    }
    for (int i = MAX_RECORD; i < MAX_RECORD * 2; ++i) {
      // Note that we have to invert the byte check here because MAX_RECORD is not divisible by two.
      assertThat(record[i]).isEqualTo((i % 2 == 0) ? 0 : 2);
    }
    for (int i = MAX_RECORD * 2; i < MAX_RECORD * 3; ++i) {
      assertThat(record[i]).isEqualTo((i % 2 == 1) ? 0 : 3);
    }
  }

  @Test
  void readFromMultiBlockStream() throws IOException {
    TestBlock block0 = makeBlockOfRepeatingBytes(0);
    TestBlock block1 = makeBlockOfRepeatingBytes(138);
    assertThat(readIncrementally(block0.data, block1.data))
        .hasSize(block0.recordCount + block1.recordCount);
  }

  @Test
  void read_noData() throws IOException {
    assertThat(readIncrementally(new byte[0])).isEmpty();
  }

  @Test
  void read_failBadFirstBlock() {
    assertThrows(IllegalStateException.class, () -> readIncrementally(new byte[1]));
  }

  @Test
  void read_failBadTrailingBlock() {
    TestBlock block = makeBlockOfRepeatingBytes(0);
    assertThrows(IllegalStateException.class, () -> readIncrementally(block.data, new byte[2]));
  }

  @Test
  void testChunkTypesToCode() {
    // Verify that we're translating chunk types to code values correctly.z
    assertThat(ChunkType.fromCode(ChunkType.END.getCode())).isEqualTo(ChunkType.END);
    assertThat(ChunkType.fromCode(ChunkType.FULL.getCode())).isEqualTo(ChunkType.FULL);
    assertThat(ChunkType.fromCode(ChunkType.FIRST.getCode())).isEqualTo(ChunkType.FIRST);
    assertThat(ChunkType.fromCode(ChunkType.MIDDLE.getCode())).isEqualTo(ChunkType.MIDDLE);
    assertThat(ChunkType.fromCode(ChunkType.LAST.getCode())).isEqualTo(ChunkType.LAST);
  }

  @SafeVarargs
  private static ImmutableList<byte[]> readIncrementally(byte[]... blocks) throws IOException {
    ByteArrayInputStream source = spy(new ByteArrayInputStream(Bytes.concat(blocks)));
    ImmutableList<byte[]> records = ImmutableList.copyOf(LevelDbLogReader.from(source));
    verify(source, times(1)).close();
    return records;
  }

  /** Aggregates the bytes of a test block with the record count. */
  private static final class TestBlock {
    final byte[] data;
    final int recordCount;

    TestBlock(byte[] data, int recordCount) {
      this.data = data;
      this.recordCount = recordCount;
    }
  }
}
