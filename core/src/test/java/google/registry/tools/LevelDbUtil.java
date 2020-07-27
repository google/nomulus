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

import static com.google.common.base.Preconditions.checkArgument;

import google.registry.tools.LevelDbLogReader.ChunkType;

class LevelDbUtil {

  static final int MAX_RECORD = LevelDbLogReader.BLOCK_SIZE - LevelDbLogReader.HEADER_SIZE;

  /** Adds a new record header to "bytes" at "pos", returns the new position. */
  private static int addRecordHeader(byte[] bytes, int pos, ChunkType type, int size) {
    // Write a bogus checksum.
    for (int i = 0; i < 4; ++i) {
      bytes[pos++] = -1;
    }

    // Write size and type.
    bytes[pos++] = (byte) size;
    bytes[pos++] = (byte) (size >> 8);
    bytes[pos++] = (byte) type.getCode();

    return pos;
  }

  /**
   * Adds a record of repeating bytes of 'val' of the given size to bytes at pos.
   *
   * <p>Writes the two least-significant bytes of 'val', alternating the order of them. So if the
   * value of 'val' is 0x1234, writes 0x12 0x34 0x34 0x12 0x12 ... If the value is greater than
   * 0xffff, it will be truncated to 16 bits.
   *
   * <p>This currently doesn't write a real checksum since we're not doing anything with that in the
   * leveldb reader.
   *
   * <p>Returns the new offset for the next block.
   */
  static int addRecord(byte[] bytes, int pos, ChunkType type, int size, int val) {
    pos = addRecordHeader(bytes, pos, type, size);

    // Write "size" bytes of data.
    for (int i = 0; i < size; ++i) {
      bytes[pos + i] = (byte) val;

      // Swap the least significant bytes in val so we can have more than 256 different same-sized
      // records.
      val = ((val >> 8) & 0xff) | ((val & 0xff) << 8);
    }

    return pos + size;
  }

  /**
   * Adds a record containing "data" to "bytes".
   *
   * <p>This currently doesn't write a real checksum since we're not doing anything with that in the
   * leveldb reader.
   *
   * <p>Returns the new offset for the next block.
   */
  static int addRecord(byte[] bytes, int pos, ChunkType type, byte[] data) {
    checkArgument(
        data.length < MAX_RECORD,
        "Record length (%s) > max record size (%s)",
        data.length,
        MAX_RECORD);
    pos = addRecordHeader(bytes, pos, type, data.length);

    // Write the contents of "data".
    System.arraycopy(data, 0, bytes, pos, data.length);

    return pos + data.length;
  }
}
