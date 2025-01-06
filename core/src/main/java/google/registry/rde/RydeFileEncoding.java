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

import static org.bouncycastle.openpgp.PGPLiteralData.BINARY;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.util.ImprovedInputStream;
import google.registry.util.ImprovedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.CheckReturnValue;
import javax.annotation.WillNotClose;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.joda.time.DateTime;

/**
 * Input/Output stream for reading/writing PGP literal data layer.
 *
 * <p>OpenPGP messages are like an onion; there can be many layers like compression and encryption.
 * It's important to wrap out plaintext in a literal data layer such as this so the code that's
 * unwrapping the onion knows when to stop.
 *
 * <p>According to escrow spec, the PGP message should contain a single tar file.
 */
final class RydeFileEncoding {

  private static final int BUFFER_SIZE = 64 * 1024;

  /**
   * Creates an OutputStream that encodes the data as a PGP file blob.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that os is the result of openCompressor and the result is used for the actual file
   * data (Ghostryde) / goes in to openTarEncoder (RyDE).
   *
   * @param os where to write the file blob. Is not closed by this object.
   * @param filename the filename to set in the file's metadata.
   * @param modified the modification time to set in the file's metadata.
   */
  @CheckReturnValue
  static ImprovedOutputStream openPgpFileWriter(
      @WillNotClose OutputStream os, String filename, DateTime modified) {
    try {
      return new ImprovedOutputStream(
          "PgpFileWriter",
          new PGPLiteralDataGenerator()
              .open(os, BINARY, filename, modified.toDate(), new byte[BUFFER_SIZE]));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Input stream to a PGP file's data that also holds the file's metadata. */
  static class PgpFileInputStream extends ImprovedInputStream {
    private final String filename;
    private final DateTime modified;

    private PgpFileInputStream(PGPLiteralData literal) {
      super("PgpFileReader", literal.getDataStream());
      filename = literal.getFileName();
      modified = new DateTime(literal.getModificationTime(), UTC);
    }

    /** Returns the name of the original file. */
    String getFilename() {
      return filename;
    }

    /** Returns the time this file was created or modified. */
    DateTime getModified() {
      return modified;
    }
  }

  /**
   * Opens an InputStream to a PGP file blob's data.
   *
   * <p>The result includes the file's metadata - the file name and modification time.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that input is the result of openDecompressor and the result is the final file
   * (Ghostryde) / goes into openTarDecoder (RyDE).
   *
   * @param input from where to read the file blob.
   */
  @CheckReturnValue
  static PgpFileInputStream openPgpFileReader(@WillNotClose InputStream input) {
    return new PgpFileInputStream(PgpUtils.readSinglePgpObject(input, PGPLiteralData.class));
  }

  private RydeFileEncoding() {}
}
