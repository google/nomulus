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

import static org.bouncycastle.bcpg.CompressionAlgorithmTags.ZIP;

import google.registry.util.ImprovedInputStream;
import google.registry.util.ImprovedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.CheckReturnValue;
import javax.annotation.WillNotClose;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPException;

/**
 * OpenPGP compression service that wraps an {@link OutputStream}.
 *
 * <p>This uses the ZIP compression algorithm per the ICANN escrow specification.
 */
final class RydeCompression {

  private static final int BUFFER_SIZE = 64 * 1024;

  /**
   * Compression algorithm to use when creating RyDE files.
   *
   * <p>We're going to use ZIP because that's what the spec mandates.
   *
   * @see org.bouncycastle.bcpg.CompressionAlgorithmTags
   */
  private static final int COMPRESSION_ALGORITHM = ZIP;

  /**
   * Creates an OutputStream that compresses the data.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that os is the result of openEncryptor and the result goes into openFileEncoder.
   */
  @CheckReturnValue
  static ImprovedOutputStream openCompressor(@WillNotClose OutputStream os) {
    try {
      return new ImprovedOutputStream(
          "RydeCompressor",
          new PGPCompressedDataGenerator(COMPRESSION_ALGORITHM).open(os, new byte[BUFFER_SIZE]));
    } catch (IOException | PGPException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates an InputStream that decompresses the data.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that input is the result of openDecryptor and the result goes into openFileDecoder.
   */
  @CheckReturnValue
  static ImprovedInputStream openDecompressor(@WillNotClose InputStream input) {
    try {
      PGPCompressedData compressed = PgpUtils.readSinglePgpObject(input, PGPCompressedData.class);
      return new ImprovedInputStream("RydeDecompressor", compressed.getDataStream());
    } catch (PGPException e) {
      throw new RuntimeException(e);
    }
  }
}
