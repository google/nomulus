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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.rde.RydeCompression.openCompressor;
import static google.registry.rde.RydeEncryption.RYDE_USE_INTEGRITY_PACKET;
import static google.registry.rde.RydeEncryption.openEncryptor;
import static google.registry.rde.RydeFileEncoding.openPgpFileWriter;
import static google.registry.rde.RydeTar.openTarWriter;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import javax.annotation.concurrent.NotThreadSafe;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;

/**
 * Stream that performs the full RyDE encryption.
 *
 * <p>The RyDE format has 2 files:
 *
 * <ul>
 *   <li>the "data" file, encoded in data -&gt; tar -&gt; PgpFile -&gt; compression -&gt; encryption
 *   <li>the signature of the resulting file
 * </ul>
 *
 * <p>Hence, the encoder needs to receive 2 OutputStreams - one for the data and one for the
 * signature.
 *
 * <p>Because of the external tar file encoding - the encoder must know the total length of the data
 * from the start. This is a bit annoying, but necessary.
 */
@NotThreadSafe
public final class RydeEncoder extends FilterOutputStream {

  private final OutputStream sigOutput;
  private final RydePgpSigningOutputStream signer;
  // We use a Closer to handle the stream .close, to make sure it's done correctly.
  private final Closer closer = Closer.create();
  private boolean isClosed = false;

  private RydeEncoder(
      OutputStream rydeOutput,
      OutputStream sigOutput,
      long dataLength,
      String filenamePrefix,
      DateTime modified,
      PGPKeyPair signingKey,
      Collection<PGPPublicKey> receiverKeys) {
    super(null);
    this.sigOutput = sigOutput;
    signer = closer.register(new RydePgpSigningOutputStream(checkNotNull(rydeOutput), signingKey));
    OutputStream encryptLayer =
        closer.register(openEncryptor(signer, RYDE_USE_INTEGRITY_PACKET, receiverKeys));
    OutputStream kompressor = closer.register(openCompressor(encryptLayer));
    OutputStream fileLayer =
        closer.register(openPgpFileWriter(kompressor, filenamePrefix + ".tar", modified));
    this.out =
        closer.register(openTarWriter(fileLayer, dataLength, filenamePrefix + ".xml", modified));
  }

  /**
   * Call the underlying 3 input write.
   *
   * <p>FilterInputStream implements the 3 input write using a for loop over the single-byte write.
   * For efficiency reasons, we want it to use the 3 input write instead.
   */
  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
  }

  @Override
  public void close() throws IOException {
    if (isClosed) {
      return;
    }
    // Close all the streams we opened
    closer.close();
    isClosed = true;
    try {
      sigOutput.write(signer.getSignature());
    } catch (PGPException e) {
      throw new RuntimeException("Failed to generate signature", e);
    }
  }

  /** Builder for {@link RydeEncoder}. */
  public static class Builder {
    OutputStream rydeOutput;
    OutputStream sigOutput;
    Long dataLength;
    String filenamePrefix;
    DateTime modified;
    PGPKeyPair signingKey;
    ImmutableList<PGPPublicKey> receiverKeys;

    /** Sets the OutputStream for the Ryde-encoded data, and the keys used for the encryption. */
    public Builder setRydeOutput(
        OutputStream rydeOutput, PGPPublicKey receiverKey, PGPPublicKey... moreReceiverKeys) {
      this.rydeOutput = rydeOutput;
      this.receiverKeys =
          new ImmutableList.Builder<PGPPublicKey>().add(receiverKey).add(moreReceiverKeys).build();
      return this;
    }

    /** Sets the OutputStream for the signature, and the key used to sign. */
    public Builder setSignatureOutput(OutputStream sigOutput, PGPKeyPair signingKey) {
      this.sigOutput = sigOutput;
      this.signingKey = signingKey;
      return this;
    }

    /** Sets the information about the unencoded data that will follow. */
    public Builder setFileMetadata(String filenamePrefix, long dataLength, DateTime modified) {
      this.filenamePrefix = filenamePrefix;
      this.dataLength = dataLength;
      this.modified = modified;
      return this;
    }

    /** Returns the built {@link RydeEncoder}. */
    public RydeEncoder build() {
      return new RydeEncoder(
          checkNotNull(rydeOutput, "Must call 'setRydeOutput'"),
          checkNotNull(sigOutput, "Must call 'setSignatureOutput'"),
          checkNotNull(dataLength, "Must call 'setFileMetadata'"),
          checkNotNull(filenamePrefix, "Must call 'setFileMetadata'"),
          checkNotNull(modified, "Must call 'setFileMetadata'"),
          checkNotNull(signingKey, "Must call 'setSignatureOutput'"),
          checkNotNull(receiverKeys, "Must call 'setRydeOutput'"));
    }
  }
}
