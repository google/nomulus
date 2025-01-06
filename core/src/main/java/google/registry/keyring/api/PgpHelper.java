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

package google.registry.keyring.api;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.DSA;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.ELGAMAL_GENERAL;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_GENERAL;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_SIGN;

import com.google.common.base.VerifyException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRing;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;

/** Helper functions for extracting PGP keys from their keyrings. */
public final class PgpHelper {

  /**
   * Narrowed key search requirements.
   * @see PgpHelper#lookupPublicKey
   */
  public enum KeyRequirement { ENCRYPT, SIGN, ENCRYPT_SIGN }

  /** Converts {@code publicKey} to bytes. */
  public static byte[] convertPublicKeyToBytes(PGPPublicKey publicKey) {
    try {
      return publicKey.getEncoded();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns raw key bytes as a Bouncy Castle PGP public key. */
  public static PGPPublicKey loadPublicKeyBytes(byte[] data) {
    try {
      return lookupPublicSubkey(new BcPGPPublicKeyRing(data), KeyRequirement.ENCRYPT).get();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Search for public key on keyring based on a substring (like an email address).
   *
   * @throws VerifyException if the key couldn't be found.
   * @see #lookupKeyPair
   */
  public static PGPPublicKey lookupPublicKey(
      PGPPublicKeyRingCollection keyring, String query, KeyRequirement want) {
    Iterator<PGPPublicKeyRing> results =
        keyring.getKeyRings(checkNotNull(query, "query"), true, true);
    verify(results.hasNext(), "No public key found matching substring: %s", query);
    while (results.hasNext()) {
      Optional<PGPPublicKey> result = lookupPublicSubkey(results.next(), want);
      if (result.isPresent()) {
        return result.get();
      }
    }
    throw new VerifyException(
        String.format("No public key (%s) found matching substring: %s", want, query));
  }

  /**
   * Same as {@link #lookupPublicKey} but also retrieves the associated private key.
   *
   * @throws VerifyException if either keys couldn't be found.
   * @see #lookupPublicKey
   */
  public static PGPKeyPair lookupKeyPair(
      PGPPublicKeyRingCollection publics,
      PGPSecretKeyRingCollection privates,
      String query,
      KeyRequirement want) {
    PGPPublicKey publicKey = lookupPublicKey(publics, query, want);
    PGPPrivateKey privateKey;
    try {
      PGPSecretKey secret =
          verifyNotNull(
              privates.getSecretKey(publicKey.getKeyID()),
              "Keyring missing private key associated with public key id: %s (query %s)",
              publicKey.getKeyID(),
              query);
      // We do not support putting a password on the private key so we're just going to
      // put char[0] here.
      privateKey = secret.extractPrivateKey(
          new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
              .build(new char[0]));
    } catch (PGPException e) {
      throw new VerifyException(String.format("Could not load PGP private key for: %s", query), e);
    }
    return new PGPKeyPair(publicKey, privateKey);
  }

  /**
   * Return appropriate key or subkey for given task from public key.
   *
   * <p>Weirder older PGP public keys will actually have multiple keys. The main key will usually
   * be sign-only in such situations. So you've gotta go digging in through the key packets and
   * make sure you get the one that's valid for encryption, or whatever you want to do.
   */
  public static Optional<PGPPublicKey> lookupPublicSubkey(
      PGPPublicKeyRing ring, KeyRequirement want) {
    Iterator<PGPPublicKey> keys = ring.getPublicKeys();
    while (keys.hasNext()) {
      PGPPublicKey key = keys.next();
      switch (want) {
        case ENCRYPT -> {
          if (key.isEncryptionKey()) {
            return Optional.of(key);
          }
        }
        case SIGN -> {
          if (isSigningKey(key)) {
            return Optional.of(key);
          }
        }
        case ENCRYPT_SIGN -> {
          if (key.isEncryptionKey() && isSigningKey(key)) {
            return Optional.of(key);
          }
        }
        default -> throw new AssertionError();
      }
    }
    return Optional.empty();
  }

  /** Returns {@code true} if this key can be used for signing. */
  public static boolean isSigningKey(PGPPublicKey key) {
    return switch (key.getAlgorithm()) {
      case RSA_GENERAL, RSA_SIGN, DSA, ELGAMAL_GENERAL -> true;
      default -> false;
    };
  }
}
