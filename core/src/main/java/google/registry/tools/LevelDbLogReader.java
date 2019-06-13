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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads records from a set of LevelDB files and builds a gigantic ImmutableList from them.
 *
 * <p>See <a
 * href="https://github.com/google/leveldb/blob/master/doc/log_format.md">log_format.md</a> for the
 * leveldb log format specification.</a>
 *
 * <p>There are several other implementations of this, none of which appeared suitable for our use
 * case: <a href="https://github.com/google/leveldb">The original C++ implementation</a>. <a
 * href="https://cloud.google.com/appengine/docs/standard/java/javadoc/com/google/appengine/api/files/RecordWriteChannel">
 * com.google.appengine.api.files.RecordWriteChannel</a> - Exactly what we need but deprecated. The
 * referenced replacement: <a
 * href="https://github.com/GoogleCloudPlatform/appengine-gcs-client.git">The App Engine GCS
 * Client</a> - Does not appear to have any support for working with LevelDB.
 */
public final class LevelDbLogReader {

  @VisibleForTesting static final int BLOCK_SIZE = 32 * 1024;
  @VisibleForTesting static final int HEADER_SIZE = 7;

  private final ByteArrayOutputStream recordContents = new ByteArrayOutputStream();
  private final ImmutableList.Builder<byte[]> recordListBuilder = new ImmutableList.Builder<>();

  /** Read a complete block, which must be exactly 32 KB. */
  private void processBlock(byte[] block) {
    // Read records from the block until there is no longer enough space for a record (i.e. until
    // we're at HEADER_SIZE - 1 bytes from the end of the block).
    int i = 0;
    while (i < BLOCK_SIZE - (HEADER_SIZE - 1)) {
      RecordHeader recordHeader = readRecordHeader(block, i);
      if (recordHeader.type == ChunkType.END) {
        // A type of zero indicates that we've reached the padding zeroes at the end of the block.
        break;
      }

      // Copy the contents of the record into recordContents.
      recordContents.write(block, i + HEADER_SIZE, recordHeader.size);

      // If this is the last (or only) chunk in the record, store the full contents into the List.
      if (recordHeader.type == ChunkType.FULL || recordHeader.type == ChunkType.LAST) {
        recordListBuilder.add(recordContents.toByteArray());
        recordContents.reset();
      }

      i += recordHeader.size + HEADER_SIZE;
    }
  }

  /**
   * Gets a byte from "block" as an unsigned value.
   *
   * <p>Java bytes are signed, which doesn't work very well for our bit-shifting operations.
   */
  private int getUnsignedByte(byte[] block, int pos) {
    return block[pos] & 0xFF;
  }

  /** Reads the 7 byte record header. */
  private RecordHeader readRecordHeader(byte[] block, int pos) {
    // Read checksum (4 bytes, LE).
    int checksum =
        getUnsignedByte(block, pos)
            | (getUnsignedByte(block, pos + 1) << 8)
            | (getUnsignedByte(block, pos + 2) << 16)
            | (getUnsignedByte(block, pos + 3) << 24);
    // Read size (2 bytes, LE).
    int size = getUnsignedByte(block, pos + 4) | (getUnsignedByte(block, pos + 5) << 8);
    // Read type (1 byte).
    int type = getUnsignedByte(block, pos + 6);

    return new RecordHeader(checksum, size, ChunkType.fromCode(type));
  }

  /** Reads all records in the Reader into the record set. */
  public void readFrom(InputStream source) throws IOException {
    byte[] block = new byte[BLOCK_SIZE];

    // read until we have no more.
    while (true) {
      int amountRead = source.read(block, 0, BLOCK_SIZE);
      if (amountRead <= 0) {
        break;
      }
      assert amountRead == BLOCK_SIZE;

      processBlock(block);
    }
  }

  /** Reads all records from the file specified by "path" into the record set. */
  public void readFrom(Path path) throws IOException {
    readFrom(Files.newInputStream(path));
  }

  /** Reads all records from the specified file into the record set. */
  public void readFrom(String filename) throws IOException {
    readFrom(FileSystems.getDefault().getPath(filename));
  }

  /**
   * Gets the list of records constructed so far.
   *
   * <p>Note that this does not invalidate the internal state of the object: we return a copy and
   * this can be called multiple times.
   */
  ImmutableList<byte[]> getRecords() {
    return recordListBuilder.build();
  }

  /** Aggregates the fields in a record header. */
  private static final class RecordHeader {
    final int checksum;
    final int size;
    final ChunkType type;

    public RecordHeader(int checksum, int size, ChunkType type) {
      this.checksum = checksum;
      this.size = size;
      this.type = type;
    }
  }

  @VisibleForTesting
  enum ChunkType {
    // Warning: these values must map to their array indices.  If this relationship is broken,
    // you'll need to change fromCode() to not simply index into values().
    END(0),
    FULL(1),
    FIRST(2),
    MIDDLE(3),
    LAST(4);

    private final int code;

    ChunkType(int code) {
      this.code = code;
    }

    int getCode() {
      return code;
    }

    /** Construct a record type from the numeric record type code. */
    static ChunkType fromCode(int code) {
      return values()[code];
    }
  }
}
