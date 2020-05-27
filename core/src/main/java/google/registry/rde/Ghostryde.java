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

package google.registry.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.rde.RydeCompression.openCompressor;
import static google.registry.rde.RydeCompression.openDecompressor;
import static google.registry.rde.RydeEncryption.GHOSTRYDE_USE_INTEGRITY_PACKET;
import static google.registry.rde.RydeEncryption.openDecryptor;
import static google.registry.rde.RydeEncryption.openEncryptor;
import static google.registry.rde.RydeFileEncoding.openPgpFileReader;
import static google.registry.rde.RydeFileEncoding.openPgpFileWriter;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import google.registry.util.ImprovedInputStream;
import google.registry.util.ImprovedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nullable;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;

/**
 * Utility class for reading and writing data in the ghostryde container format.
 *
 * <p>Whenever we stage sensitive data to cloud storage (like XML RDE deposit data), we
 * <a href="http://youtu.be/YPNJjL9iznY">GHOST RYDE IT</a> first to keep it safe from the prying
 * eyes of anyone with access to the <a href="https://cloud.google.com/console">Google Cloud
 * Console</a>.
 *
 * <p>The encryption is similar to the "regular" RyDE RDE deposit file encryption. The main
 * difference (and the reason we had to create a custom encryption) is that the RDE deposit has a
 * tar file in the encoding. A tar file needs to know its final size in the header, which means we
 * have to create the entire deposit before we can start encoding it.
 *
 * <p>Deposits are big, and there's no reason to hold it all in memory. Instead, save a "staging"
 * version encrypted with Ghostryde instead of "RyDE" (the RDE encryption/encoding), using the
 * "rde-staging" keys. We also remember the actual data size during the staging creation.
 *
 * <p>Then when we want to create the actual deposits, we decrypt the staging version, and using the
 * saved value for the data size we can encrypt with "RyDE" using the receiver key.
 *
 * <p>Here's how you write a file:
 *
 * <pre>   {@code
 * File in = new File("lol.txt");
 * File out = new File("lol.txt.ghostryde");
 * File lengthOut = new File("lol.length.ghostryde");
 * try (OutputStream output = new FileOutputStream(out);
 *     OutputStream lengthOutput = new FileOutputStream(lengthOut);
 *     OutputStream ghostrydeEncoder = Ghostryde.encoder(output, publicKey, lengthOut);
 *     InputStream input = new FileInputStream(in)) &lbrace;
 *   ByteStreams.copy(input, ghostrydeEncoder);
 * &rbrace;}</pre>
 *
 * <p>Here's how you read a file:
 *
 * <pre>   {@code
 * File in = new File("lol.txt.ghostryde");
 * File out = new File("lol.txt");
 * Ghostryde ghost = new Ghostryde(1024);
 * try (InputStream fileInput = new FileInputStream(in);
 *     InputStream ghostrydeDecoder = new Ghostryde.decoder(fileInput, privateKey);
 *     OutputStream fileOutput = new FileOutputStream(out)) &lbrace;
 *   ByteStreams.copy(ghostryderDecoder, fileOutput);
 * &rbrace;}</pre>
 *
 * <h2>Simple API</h2>
 *
 * <p>If you're writing test code or are certain your data can fit in memory, you might find these
 * static methods more convenient:
 *
 * <pre>   {@code
 * byte[] data = "hello kitty".getBytes(UTF_8);
 * byte[] blob = Ghostryde.encode(data, publicKey);
 * byte[] result = Ghostryde.decode(blob, privateKey);
 *
 * }</pre>
 *
 * <h2>GhostRYDE Format</h2>
 *
 * <p>A {@code .ghostryde} file is the exact same thing as a {@code .gpg} file, except the OpenPGP
 * message layers will always be present and in a specific order. You can analyse the layers on the
 * command-line using the {@code gpg --list-packets blah.ghostryde} command.
 *
 * <p>Ghostryde is different from RyDE in the sense that ghostryde is only used for <i>internal</i>
 * storage; whereas RyDE is meant to protect data being stored by a third-party.
 */
public final class Ghostryde {

  /**
   * For backwards compatibility reasons, we wrap the data in a PGP file, which preserves the
   * original filename and modification time. However, these values are never used, so we just
   * set them to a constant value.
   */
  static final String INNER_FILENAME = "file.xml";
  static final DateTime INNER_MODIFICATION_TIME = DateTime.parse("2000-01-01TZ");

  /**
   * Creates a ghostryde file from an in-memory byte array.
   */
  public static byte[] encode(byte[] data, PGPPublicKey key)
      throws IOException, PGPException {
    checkNotNull(data, "data");
    checkArgument(key.isEncryptionKey(), "not an encryption key");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (OutputStream encoder = encoder(output, key)) {
      encoder.write(data);
    }
    return output.toByteArray();
  }

  /**
   * Deciphers a ghostryde file from an in-memory byte array.
   */
  public static byte[] decode(byte[] data, PGPPrivateKey key)
      throws IOException, PGPException {
    checkNotNull(data, "data");
    ByteArrayInputStream dataStream = new ByteArrayInputStream(data);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream ghostrydeDecoder = decoder(dataStream, key)) {
      ByteStreams.copy(ghostrydeDecoder, output);
    }
    return output.toByteArray();
  }

  /** Reads the value of a length stream - see {@link #encoder}. */
  public static long readLength(InputStream lengthStream) throws IOException {
    return Long.parseLong(new String(ByteStreams.toByteArray(lengthStream), UTF_8).trim());
  }

  /**
   * Creates a Ghostryde Encoder.
   *
   * <p>Optionally can also save the total length of the data written to an OutputStream.
   *
   * <p>This is necessary because the RyDE format uses a tar file which requires the total length in
   * the header. We don't want to have to decrypt the entire ghostryde file to determine the length,
   * so we just save it separately.
   *
   * @param output where to write the encrypted data
   * @param encryptionKey the encryption key to use
   * @param lengthOutput if not null - will save the total length of the data written to this
   *     output. See {@link #readLength}.
   */
  public static ImprovedOutputStream encoder(
      OutputStream output, PGPPublicKey encryptionKey, @Nullable OutputStream lengthOutput) {

    // We use a Closer to handle the stream .close, to make sure it's done correctly.
    Closer closer = Closer.create();
    OutputStream encryptionLayer =
        closer.register(
            openEncryptor(output, GHOSTRYDE_USE_INTEGRITY_PACKET, ImmutableList.of(encryptionKey)));
    OutputStream kompressor = closer.register(openCompressor(encryptionLayer));
    OutputStream fileLayer =
        closer.register(openPgpFileWriter(kompressor, INNER_FILENAME, INNER_MODIFICATION_TIME));

    return new ImprovedOutputStream("GhostrydeEncoder", fileLayer) {
      @Override
      public void onClose() throws IOException {
        // Close all the streams we opened
        closer.close();
        // Optionally also output the size of the encoded data - which is needed for the RyDE
        // encoding.
        if (lengthOutput != null) {
          lengthOutput.write(Long.toString(getBytesWritten()).getBytes(US_ASCII));
        }
      }
    };
  }

  /**
   * Creates a Ghostryde Encoder.
   *
   * @param output where to write the encrypted data
   * @param encryptionKey the encryption key to use
   */
  public static ImprovedOutputStream encoder(OutputStream output, PGPPublicKey encryptionKey) {
    return encoder(output, encryptionKey, null);
  }

  /**
   * Creates a Ghostryde decoder.
   *
   * @param input from where to read the encrypted data
   * @param decryptionKey the decryption key to use
   */
  public static ImprovedInputStream decoder(InputStream input, PGPPrivateKey decryptionKey) {

    // We use a Closer to handle the stream .close, to make sure it's done correctly.
    Closer closer = Closer.create();
    InputStream decryptionLayer =
        closer.register(openDecryptor(input, GHOSTRYDE_USE_INTEGRITY_PACKET, decryptionKey));
    InputStream decompressor = closer.register(openDecompressor(decryptionLayer));
    InputStream fileLayer = closer.register(openPgpFileReader(decompressor));

    return new ImprovedInputStream("GhostryderDecoder", fileLayer) {
      @Override
      public void onClose() throws IOException {
        // Close all the streams we opened
        closer.close();
      }
    };
  }

  private Ghostryde() {}
}
