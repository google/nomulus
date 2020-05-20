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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Optional;

/**
 * Iterator that incrementally parses binary data in LevelDb format into records.
 *
 * <p>See <a
 * href="https://github.com/google/leveldb/blob/master/doc/log_format.md">log_format.md</a> for the
 * leveldb log format specification.</a>
 *
 * <p>There are several other implementations of this, none of which appeared suitable for our use
 * case: <a href="https://github.com/google/leveldb">The original C++ implementation</a>. <a
 * href="https://cloud.google.com/appengine/docs/standard/java/javadoc/com/google/appengine/api/files/RecordReadChannel">
 * com.google.appengine.api.files.RecordReadChannel</a> - Exactly what we need but deprecated. The
 * referenced replacement: <a
 * href="https://github.com/GoogleCloudPlatform/appengine-gcs-client.git">The App Engine GCS
 * Client</a> - Does not appear to have any support for working with LevelDB.
 */
public final class LevelDbLogReader implements Iterator<byte[]> {

  @VisibleForTesting static final int BLOCK_SIZE = 32 * 1024;
  @VisibleForTesting static final int HEADER_SIZE = 7;

  private final ByteArrayOutputStream recordContents = new ByteArrayOutputStream();
  private final LinkedList<byte[]> recordList = Lists.newLinkedList();

  private final ByteBuffer byteBuffer = ByteBuffer.allocate(BLOCK_SIZE);
  private final ReadableByteChannel channel;

  LevelDbLogReader(ReadableByteChannel channel) {
    this.channel = channel;
  }

  @Override
  public boolean hasNext() {
    while (recordList.isEmpty()) {
      try {
        Optional<byte[]> block = readFromChannel();
        if (!block.isPresent()) {
          return false;
        }
        if (block.get().length != BLOCK_SIZE) {
          throw new IllegalStateException("Data size is not multiple of " + BLOCK_SIZE);
        }
        processBlock(block.get());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return true;
  }

  @Override
  public byte[] next() {
    checkState(hasNext(), "The next() method called on empty iterator.");
    return recordList.removeFirst();
  }

  /**
   * Returns the next {@link #BLOCK_SIZE} bytes from the input channel, or {@link
   * Optional#empty()} if there is no more data.
   */
  // TODO(weiminyu): use ByteBuffer directly.
  private Optional<byte[]> readFromChannel() throws IOException {
    while (true) {
      int bytesRead = channel.read(byteBuffer);
      if (!byteBuffer.hasRemaining() || bytesRead < 0) {
        byteBuffer.flip();
        if (!byteBuffer.hasRemaining()) {
          return Optional.empty();
        }
        byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);
        byteBuffer.clear();
        return Optional.of(result);
      }
    }
  }

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
        recordList.add(recordContents.toByteArray());
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

  /** Returns a {@link LevelDbLogReader} over a {@link ReadableByteChannel}. */
  public static LevelDbLogReader from(ReadableByteChannel channel) {
    return new LevelDbLogReader(channel);
  }

  /** Returns a {@link LevelDbLogReader} over an {@link InputStream}. */
  public static LevelDbLogReader from(InputStream source) {
    return new LevelDbLogReader(Channels.newChannel(source));
  }

  /** Returns a {@link LevelDbLogReader} over a file specified by {@link Path}. */
  public static LevelDbLogReader from(Path path) throws IOException {
    return from(Files.newInputStream(path));
  }

  /** Returns a {@link LevelDbLogReader} over a file specified by {@code filename}. */
  public static LevelDbLogReader from(String filename) throws IOException {
    return from(FileSystems.getDefault().getPath(filename));
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
