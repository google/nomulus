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

package google.registry.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.io.ByteStreams;
import google.registry.util.ImprovedInputStream;
import google.registry.util.ImprovedOutputStream;
import google.registry.util.PosixTarHeader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.CheckReturnValue;
import javax.annotation.WillNotClose;
import org.joda.time.DateTime;

/** Single-file POSIX tar archive creator that wraps an {@link OutputStream}. */
final class RydeTar {

  /**
   * Creates a new {@link ImprovedOutputStream} that creates a tar archive with a single file.
   *
   * @param os is the upstream {@link OutputStream} which is not closed by this object
   * @param expectedSize is the length in bytes of the one file, which you will write to this object
   * @param modified is the {@link PosixTarHeader.Builder#setMtime mtime} you want to set
   * @param filename is the name of the one file that will be contained in this archive
   */
  @CheckReturnValue
  static ImprovedOutputStream openTarWriter(
      @WillNotClose OutputStream os, long expectedSize, String filename, DateTime modified) {

    checkArgument(expectedSize >= 0);
    checkArgument(filename.endsWith(".xml"),
        "Ryde expects tar archive to contain a filename with an '.xml' extension.");
    try {
      os.write(new PosixTarHeader.Builder()
          .setName(filename)
          .setSize(expectedSize)
          .setMtime(modified)
          .build()
          .getBytes());
      return new ImprovedOutputStream("RydeTarWriter", os) {
        /** Writes the end of archive marker. */
        @Override
        public void onClose() throws IOException {
          if (getBytesWritten() != expectedSize) {
            throw new IOException(
                String.format(
                    "RydeTarOutputStream expected %,d bytes, but got %,d bytes",
                    expectedSize, getBytesWritten()));
          }
          // Round up to a 512-byte boundary and another 1024-bytes to indicate end of archive.
          out.write(new byte[1024 + 512 - (int) (getBytesWritten() % 512L)]);
        }
      };
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Input stream to a TAR archive file's data that also holds the file's metadata. */
  static final class TarInputStream extends ImprovedInputStream {
    private final PosixTarHeader header;

    private TarInputStream(PosixTarHeader header, InputStream input) {
      super("RydeTarReader", input);
      this.header = header;
    }

    /** Returns the file's TAR header. */
    PosixTarHeader getHeader() {
      return header;
    }

    /** Returns the original name of the file archived in this TAR. */
    String getFilename() {
      return header.getName();
    }

    /** Returns the creation/modification time of the file archived in this TAR. */
    DateTime getModified() {
      return header.getMtime();
    }
  }

  /**
   * Opens a stream to the first file archived in a TAR archive.
   *
   * <p>The result includes the file's metadata - the file name and modification time, as well as
   * the full TAR header. Note that only the file name and modification times were actually set by
   * {@link #openTarWriter}.
   *
   * @param input from where to read the TAR archive.
   */
  @CheckReturnValue
  static TarInputStream openTarReader(@WillNotClose InputStream input) {
    try {
      byte[] header = new byte[PosixTarHeader.HEADER_LENGTH];
      ByteStreams.readFully(input, header, 0, header.length);
      PosixTarHeader tarHeader = PosixTarHeader.from(header);
      checkState(
          tarHeader.getType() == PosixTarHeader.Type.REGULAR,
          "Only support TAR archives with a single regular file");
      return new TarInputStream(tarHeader, ByteStreams.limit(input, tarHeader.getSize()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
