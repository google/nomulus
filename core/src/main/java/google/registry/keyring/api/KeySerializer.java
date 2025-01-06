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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRing;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;

/**
 * Collection of tools to serialize / deserialize PGP types.
 *
 * <p>There is no way to serialize / deserialize a PGPPrivateKey on its own, you have to pair it
 * with the public key.
 */
public final class KeySerializer {


  private KeySerializer() {}

  /**
   * Serialize a PGPPublicKey
   *
   * <p>The reason we're not using {@link PGPPublicKey#getEncoded()} is to use {@link
   * ArmoredOutputStream}.
   */
  public static byte[] serializePublicKey(PGPPublicKey publicKey) throws IOException {
    try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
      // NOTE: We have to close the ArmoredOutputStream before calling the underlying OutputStream's
      // "toByteArray". Failing to do so would result in a truncated serialization as we took the
      // byte array before the ArmoredOutputStream wrote all the data.
      //
      // Even "flushing" the ArmoredOutputStream isn't enough - as there are parts that are only
      // written by the ArmoredOutputStream when it is closed: the "-----END PGP PRIVATE KEY
      // BLOCK-----" (or similar) footer.
      try (ArmoredOutputStream out = new ArmoredOutputStream(byteStream)) {
        publicKey.encode(out);
      }
      return byteStream.toByteArray();
    }
  }

  /** Deserialize a PGPPublicKey */
  public static PGPPublicKey deserializePublicKey(byte[] serialized) throws IOException {
    return
        new BcPGPPublicKeyRing(
            PGPUtil.getDecoderStream(
                new ByteArrayInputStream(serialized))).getPublicKey();
  }

  /** Serializes a string */
  public static byte[] serializeString(String key) {
    return key.getBytes(UTF_8);
  }

  /** Deserializes a string */
  public static String deserializeString(byte[] serialized) {
    return new String(serialized, UTF_8);
  }

  /**
   * Serialize a PGPKeyPair
   *
   * <p>Use this to serialize a PGPPrivateKey as well (pairing it with the corresponding
   * PGPPublicKey), as private keys can't be serialized on their own.
   */
  public static byte[] serializeKeyPair(PGPKeyPair keyPair) throws IOException, PGPException {
    try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
      // NOTE: We have to close the ArmoredOutputStream before calling the underlying OutputStream's
      // "toByteArray". Failing to do so would result in a truncated serialization as we took the
      // byte array before the ArmoredOutputStream wrote all the data.
      //
      // Even "flushing" the ArmoredOutputStream isn't enough - as there are parts that are only
      // written by the ArmoredOutputStream when it is closed: the "-----END PGP PRIVATE KEY
      // BLOCK-----" (or similar) footer.
      try (ArmoredOutputStream out = new ArmoredOutputStream(byteStream)) {
        new PGPSecretKey(
            keyPair.getPrivateKey(),
            keyPair.getPublicKey(),
            new JcaPGPDigestCalculatorProviderBuilder()
                .setProvider("BC")
                .build()
                .get(HashAlgorithmTags.SHA256),
            true,
            null).encode(out);
      }
      return byteStream.toByteArray();
    }
  }

  /** Deserialize a PGPKeyPair */
  public static PGPKeyPair deserializeKeyPair(byte[] serialized)
      throws IOException, PGPException {
    PGPSecretKey secretKey =
        new BcPGPSecretKeyRing(
            PGPUtil.getDecoderStream(
                new ByteArrayInputStream(serialized))).getSecretKey();
    return new PGPKeyPair(
        secretKey.getPublicKey(),
        secretKey.extractPrivateKey(createSecretKeyDecryptor()));
  }

  private static PBESecretKeyDecryptor createSecretKeyDecryptor() {
    // There shouldn't be a passphrase on the key
    return new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
        .build(new char[0]);
  }
}
