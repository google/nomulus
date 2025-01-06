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

import static org.bouncycastle.bcpg.HashAlgorithmTags.SHA256;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_GENERAL;
import static org.bouncycastle.openpgp.PGPSignature.BINARY_DOCUMENT;

import google.registry.util.ImprovedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import javax.annotation.WillNotClose;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;

/**
 * OpenPGP detached signing service that wraps an {@link OutputStream}.
 *
 * <p>This is the outermost layer. It signs the resulting file without modifying its bytes,
 * instead generating an out-of-band {@code .asc} signature file. This is basically a SHA-256
 * checksum of the deposit file that's signed with our RSA private key. This allows the people
 * who receive a deposit to check the signature against our public key so they can know the
 * data hasn't been forged.
 */
public class RydePgpSigningOutputStream extends ImprovedOutputStream {

  private final PGPSignatureGenerator signer;

  /**
   * Create a signer that wraps {@code os} and generates a detached signature using
   * {@code signingKey}. After closing, you should call {@link #getSignature()} to get the detached
   * signature.
   *
   * @param os is the upstream {@link OutputStream} which is not closed by this object
   * @throws RuntimeException to rethrow {@link PGPException}
   */
  public RydePgpSigningOutputStream(
      @WillNotClose OutputStream os,
      PGPKeyPair signingKey) {
    super("RydePgpSigningOutputStream", os, false);
    try {
      signer = new PGPSignatureGenerator(
          new BcPGPContentSignerBuilder(RSA_GENERAL, SHA256));
      signer.init(BINARY_DOCUMENT, signingKey.getPrivateKey());
    } catch (PGPException e) {
      throw new RuntimeException(e);
    }
    addUserInfoToSignature(signingKey.getPublicKey(), signer);
  }

  /** Returns the byte contents for the detached {@code .asc} signature file. */
  public byte[] getSignature() throws IOException, PGPException {
    return signer.generate().getEncoded();
  }

  /** @see ImprovedOutputStream#write(int) */
  @Override
  public void write(int b) throws IOException {
    super.write(b);
    signer.update((byte) b);
  }

  /** @see ImprovedOutputStream#write(byte[], int, int) */
  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    super.write(b, off, len);
    signer.update(b, off, len);
  }

  /**
   * Add user ID to signature file.
   *
   * <p>This adds information about the identity of the signer to the signature file. It's not
   * required, but I'm guessing it could be a lifesaver if somewhere down the road, people lose
   * track of the public keys and need to figure out how to verify a couple blobs. This would at
   * least tell them which key to download from the MIT keyserver.
   *
   * <p>But the main reason why I'm using this is because I copied it from the code of another
   * googler who was also uncertain about the precise reason why it's needed.
   */
  private static void addUserInfoToSignature(PGPPublicKey publicKey, PGPSignatureGenerator signer) {
    Iterator<String> uidIter = publicKey.getUserIDs();
    if (uidIter.hasNext()) {
      PGPSignatureSubpacketGenerator spg = new PGPSignatureSubpacketGenerator();
      spg.setSignerUserID(false, uidIter.next());
      signer.setHashedSubpackets(spg.generate());
    }
  }
}
