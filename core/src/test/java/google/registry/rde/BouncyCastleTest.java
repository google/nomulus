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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.HexDumper.dumpHex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.bouncycastle.bcpg.CompressionAlgorithmTags.ZIP;
import static org.bouncycastle.bcpg.HashAlgorithmTags.SHA256;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.RSA_GENERAL;
import static org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags.AES_128;

import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import google.registry.testing.BouncyCastleProviderRule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Iterator;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRing;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.util.encoders.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Bouncy Castle &ndash; How does it work?!
 *
 * <p>I wrote these tests to teach myself how to use the Bouncy Castle cryptography library;
 * however I still believe these tests are useful enough to stick around in the domain registry
 * codebase because:
 *
 * <ol>
 * <li>They can serve as documentation for future maintainers about how Bouncy Castle works.
 * <li>They'll let us know if any of our assumptions about the library's behaviour are broken by
 *   future releases.
 * </ol>
 *
 * <p>Bouncy Castle is a very difficult library to use. OpenPGP (and cryptography in general!) is
 * complicated enough to begin with, but Bouncy Castle doesn't make life easier because it fails to
 * practice many of the design practices Java programmers take for granted, i.e. implementing
 * interfaces like {@link java.io.Closeable}. The only solid code examples that exist on the
 * Internet are the ones included with the library, and they don't not necessarily explain the
 * "right" way to do it. Much of what you see here I had to piece together myself by exploring the
 * Bouncy Castle code, taking tips here and there from our codebase and Stack Overflow responses.
 *
 * <p>The biggest threat we'll face in the future is that so many of the methods used in this file
 * are deprecated in the current (as of August 2013) Bouncy Castle release 1.49. We're still
 * using 1.46 so thankfully we're not far enough behind that the Bouncy Castle authors have decided
 * to remove these functions. But a migration effort will be necessary in the future.
 */
public class BouncyCastleTest {

  private static final String FALL_OF_HYPERION_A_DREAM = ""
      + "Fanatics have their dreams, wherewith they weave\n"
      + "A paradise for a sect; the savage too\n"
      + "From forth the loftiest fashion of his sleep\n"
      + "Guesses at Heaven; pity these have not\n"
      + "Trac'd upon vellum or wild Indian leaf\n"
      + "The shadows of melodious utterance.\n"
      + "But bare of laurel they live, dream, and die;\n"
      + "For Poesy alone can tell her dreams,\n"
      + "With the fine spell of words alone can save\n"
      + "Imagination from the sable charm\n"
      + "And dumb enchantment. Who alive can say,\n"
      + "'Thou art no Poet may'st not tell thy dreams?'\n"
      + "Since every man whose soul is not a clod\n"
      + "Hath visions, and would speak, if he had loved\n"
      + "And been well nurtured in his mother tongue.\n"
      + "Whether the dream now purpos'd to rehearse\n"
      + "Be poet's or fanatic's will be known\n"
      + "When this warm scribe my hand is in the grave.\n";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @RegisterExtension public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @Test
  void testCompressDecompress() throws Exception {
    // Compress the data and write out a compressed data OpenPGP message.
    byte[] data;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PGPCompressedDataGenerator kompressor = new PGPCompressedDataGenerator(ZIP);
      try (OutputStream output2 = kompressor.open(output)) {
        output2.write(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
      }
      data = output.toByteArray();
    }
    logger.atInfo().log("Compressed data: %s", dumpHex(data));

    // Decompress the data.
    try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPCompressedData object = (PGPCompressedData) pgpFact.nextObject();
      InputStream original = object.getDataStream();  // Closing this would close input.
      assertThat(CharStreams.toString(new InputStreamReader(original, UTF_8)))
          .isEqualTo(FALL_OF_HYPERION_A_DREAM);
      assertThat(pgpFact.nextObject()).isNull();
    }
  }

  @Test
  void testSignVerify_Detached() throws Exception {
    // Load the keys.
    PGPPublicKeyRing publicKeyRing = new BcPGPPublicKeyRing(PUBLIC_KEY);
    PGPSecretKeyRing privateKeyRing = new BcPGPSecretKeyRing(PRIVATE_KEY);
    PGPPublicKey publicKey = publicKeyRing.getPublicKey();
    PGPPrivateKey privateKey = extractPrivateKey(privateKeyRing.getSecretKey());

    // Sign the data and write signature data to "signatureFile".
    // Note: RSA_GENERAL will encrypt AND sign. RSA_SIGN and RSA_ENCRYPT are deprecated.
    PGPSignatureGenerator signer = new PGPSignatureGenerator(
        new BcPGPContentSignerBuilder(RSA_GENERAL, SHA256));
    signer.init(PGPSignature.BINARY_DOCUMENT, privateKey);
    addUserInfoToSignature(publicKey, signer);
    signer.update(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    signer.generate().encode(output);
    byte[] signatureFileData = output.toByteArray();
    logger.atInfo().log(".sig file data: %s", dumpHex(signatureFileData));

    // Load algorithm information and signature data from "signatureFileData".
    PGPSignature sig;
    try (ByteArrayInputStream input = new ByteArrayInputStream(signatureFileData)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPSignatureList sigList = (PGPSignatureList) pgpFact.nextObject();
      assertThat(sigList.size()).isEqualTo(1);
      sig = sigList.get(0);
    }

    // Use "onePass" and "sig" to verify "publicKey" signed the text.
    sig.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
    sig.update(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
    assertThat(sig.verify()).isTrue();

    // Verify that they DIDN'T sign the text "hello monster".
    sig.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
    sig.update("hello monster".getBytes(UTF_8));
    assertThat(sig.verify()).isFalse();
  }

  @Test
  void testSignVerify_OnePass() throws Exception {
    // Load the keys.
    PGPPublicKeyRing publicKeyRing = new BcPGPPublicKeyRing(PUBLIC_KEY);
    PGPSecretKeyRing privateKeyRing = new BcPGPSecretKeyRing(PRIVATE_KEY);
    PGPPublicKey publicKey = publicKeyRing.getPublicKey();
    PGPPrivateKey privateKey = extractPrivateKey(privateKeyRing.getSecretKey());

    // Sign the data and write signature data to "signatureFile".
    PGPSignatureGenerator signer = new PGPSignatureGenerator(
        new BcPGPContentSignerBuilder(RSA_GENERAL, SHA256));
    signer.init(PGPSignature.BINARY_DOCUMENT, privateKey);
    addUserInfoToSignature(publicKey, signer);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    signer.generateOnePassVersion(false).encode(output);
    signer.update(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
    signer.generate().encode(output);
    byte[] signatureFileData = output.toByteArray();
    logger.atInfo().log(".sig file data: %s", dumpHex(signatureFileData));

    // Load algorithm information and signature data from "signatureFileData".
    PGPSignature sig;
    PGPOnePassSignature onePass;
    try (ByteArrayInputStream input = new ByteArrayInputStream(signatureFileData)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPOnePassSignatureList onePassList = (PGPOnePassSignatureList) pgpFact.nextObject();
      PGPSignatureList sigList = (PGPSignatureList) pgpFact.nextObject();
      assertThat(onePassList.size()).isEqualTo(1);
      assertThat(sigList.size()).isEqualTo(1);
      onePass = onePassList.get(0);
      sig = sigList.get(0);
    }

    // Use "onePass" and "sig" to verify "publicKey" signed the text.
    onePass.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
    onePass.update(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
    assertThat(onePass.verify(sig)).isTrue();

    // Verify that they DIDN'T sign the text "hello monster".
    onePass.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
    onePass.update("hello monster".getBytes(UTF_8));
    assertThat(onePass.verify(sig)).isFalse();
  }

  @Test
  void testEncryptDecrypt_ExplicitStyle() throws Exception {
    int bufferSize = 64 * 1024;

    // Alice loads Bob's "publicKey" into memory.
    PGPPublicKeyRing publicKeyRing = new BcPGPPublicKeyRing(PUBLIC_KEY);
    PGPPublicKey publicKey = publicKeyRing.getPublicKey();

    // Alice encrypts the secret message for Bob using his "publicKey".
    PGPEncryptedDataGenerator encryptor = new PGPEncryptedDataGenerator(
        new BcPGPDataEncryptorBuilder(AES_128));
    encryptor.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(publicKey));
    byte[] encryptedData;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (OutputStream output2 = encryptor.open(output, new byte[bufferSize])) {
        output2.write(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
      }
      encryptedData = output.toByteArray();
    }
    logger.atInfo().log("Encrypted data: %s", dumpHex(encryptedData));

    // Bob loads his "privateKey" into memory.
    PGPSecretKeyRing privateKeyRing = new BcPGPSecretKeyRing(PRIVATE_KEY);
    PGPPrivateKey privateKey = extractPrivateKey(privateKeyRing.getSecretKey());

    // Bob decrypt's the OpenPGP message (w/ ciphertext) using his "privateKey".
    try (ByteArrayInputStream input = new ByteArrayInputStream(encryptedData)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPEncryptedDataList encDataList = (PGPEncryptedDataList) pgpFact.nextObject();
      assertThat(encDataList.size()).isEqualTo(1);
      PGPPublicKeyEncryptedData encData = (PGPPublicKeyEncryptedData) encDataList.get(0);
      assertThat(encData.getKeyID()).isEqualTo(publicKey.getKeyID());
      assertThat(encData.getKeyID()).isEqualTo(privateKey.getKeyID());
      try (InputStream original =
          encData.getDataStream(new BcPublicKeyDataDecryptorFactory(privateKey))) {
        assertThat(CharStreams.toString(new InputStreamReader(original, UTF_8)))
            .isEqualTo(FALL_OF_HYPERION_A_DREAM);
      }
    }
  }

  @Test
  void testEncryptDecrypt_KeyRingStyle() throws Exception {
    int bufferSize = 64 * 1024;

    // Alice loads Bob's "publicKey" into memory from her public key ring.
    PGPPublicKeyRingCollection publicKeyRings = new BcPGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(PUBLIC_KEY)));
    PGPPublicKeyRing publicKeyRing =
        publicKeyRings.getKeyRings("eric@bouncycastle.org", true, true).next();
    PGPPublicKey publicKey = publicKeyRing.getPublicKey();

    // Alice encrypts the secret message for Bob using his "publicKey".
    PGPEncryptedDataGenerator encryptor = new PGPEncryptedDataGenerator(
        new BcPGPDataEncryptorBuilder(AES_128));
    encryptor.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(publicKey));
    byte[] encryptedData;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (OutputStream output2 = encryptor.open(output, new byte[bufferSize])) {
        output2.write(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
      }
      encryptedData = output.toByteArray();
    }
    logger.atInfo().log("Encrypted data: %s", dumpHex(encryptedData));

    // Bob loads his chain of private keys into memory.
    PGPSecretKeyRingCollection privateKeyRings = new BcPGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(PRIVATE_KEY)));

    // Bob decrypt's the OpenPGP message (w/ ciphertext) using his "privateKey".
    try (ByteArrayInputStream input = new ByteArrayInputStream(encryptedData)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPEncryptedDataList encDataList = (PGPEncryptedDataList) pgpFact.nextObject();
      assertThat(encDataList.size()).isEqualTo(1);
      PGPPublicKeyEncryptedData encData = (PGPPublicKeyEncryptedData) encDataList.get(0);
      // Bob loads the private key to which the message is addressed.
      PGPPrivateKey privateKey =
          extractPrivateKey(privateKeyRings.getSecretKey(encData.getKeyID()));
      try (InputStream original =
          encData.getDataStream(new BcPublicKeyDataDecryptorFactory(privateKey))) {
        assertThat(CharStreams.toString(new InputStreamReader(original, UTF_8)))
            .isEqualTo(FALL_OF_HYPERION_A_DREAM);
      }
    }
  }

  @Test
  void testCompressEncryptDecryptDecompress_KeyRingStyle() throws Exception {
    int bufsz = 64 * 1024;

    // Alice loads Bob's "publicKey" into memory from her public key ring.
    PGPPublicKeyRingCollection publicKeyRings = new BcPGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(PUBLIC_KEY)));
    PGPPublicKeyRing publicKeyRing =
        publicKeyRings.getKeyRings("eric@bouncycastle.org", true, true).next();
    PGPPublicKey publicKey = publicKeyRing.getPublicKey();

    // Alice encrypts the secret message for Bob using his "publicKey".
    PGPEncryptedDataGenerator encryptor =
        new PGPEncryptedDataGenerator(new BcPGPDataEncryptorBuilder(AES_128));
    encryptor.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(publicKey));
    byte[] encryptedData;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (OutputStream output2 = encryptor.open(output, new byte[bufsz])) {
        PGPCompressedDataGenerator kompressor = new PGPCompressedDataGenerator(ZIP);
        try (OutputStream output3 = kompressor.open(output2, new byte[bufsz])) {
          output3.write(FALL_OF_HYPERION_A_DREAM.getBytes(UTF_8));
        }
      }
      encryptedData = output.toByteArray();
    }
    logger.atInfo().log("Encrypted data: %s", dumpHex(encryptedData));

    // Bob loads his chain of private keys into memory.
    PGPSecretKeyRingCollection privateKeyRings = new BcPGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(PRIVATE_KEY)));

    // Bob decrypt's the OpenPGP message (w/ ciphertext) using his "privateKey".
    try (ByteArrayInputStream input = new ByteArrayInputStream(encryptedData)) {
      PGPObjectFactory pgpFact = new BcPGPObjectFactory(input);
      PGPEncryptedDataList encDataList = (PGPEncryptedDataList) pgpFact.nextObject();
      assertThat(encDataList.size()).isEqualTo(1);
      PGPPublicKeyEncryptedData encData = (PGPPublicKeyEncryptedData) encDataList.get(0);
      // Bob loads the private key to which the message is addressed.
      PGPPrivateKey privateKey =
          extractPrivateKey(privateKeyRings.getSecretKey(encData.getKeyID()));
      try (InputStream original =
          encData.getDataStream(new BcPublicKeyDataDecryptorFactory(privateKey))) {
        pgpFact = new BcPGPObjectFactory(original);
        PGPCompressedData kompressedData = (PGPCompressedData) pgpFact.nextObject();
        try (InputStream orig2 = kompressedData.getDataStream()) {
          assertThat(CharStreams.toString(new InputStreamReader(orig2, UTF_8)))
              .isEqualTo(FALL_OF_HYPERION_A_DREAM);
        }
      }
    }
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
   * Googler who was also uncertain about the precise reason why it's needed.
   */
  private void addUserInfoToSignature(PGPPublicKey publicKey, PGPSignatureGenerator signer) {
    Iterator<String> uidIter = publicKey.getUserIDs();
    if (uidIter.hasNext()) {
      PGPSignatureSubpacketGenerator spg = new PGPSignatureSubpacketGenerator();
      spg.setSignerUserID(false, uidIter.next());
      signer.setHashedSubpackets(spg.generate());
    }
  }

  private static PGPPrivateKey extractPrivateKey(PGPSecretKey secretKey) throws PGPException {
    return secretKey.extractPrivateKey(
        new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
            .build(PASSWORD));
  }

  private static final char[] PASSWORD = "hello world".toCharArray();

  private static final byte[] PUBLIC_KEY = Base64.decode(""
      + "mIsEPz2nJAEEAOTVqWMvqYE693qTgzKv/TJpIj3hI8LlYPC6m1dk0z3bDLwVVk9F"
      + "FAB+CWS8RdFOWt/FG3tEv2nzcoNdRvjv9WALyIGNawtae4Ml6oAT06/511yUzXHO"
      + "k+9xK3wkXN5jdzUhf4cA2oGpLSV/pZlocsIDL+jCUQtumUPwFodmSHhzAAYptC9F"
      + "cmljIEVjaGlkbmEgKHRlc3Qga2V5KSA8ZXJpY0Bib3VuY3ljYXN0bGUub3JnPoi4"
      + "BBMBAgAiBQI/PackAhsDBQkAg9YABAsHAwIDFQIDAxYCAQIeAQIXgAAKCRA1WGFG"
      + "/fPzc8WMA/9BbjuB8E48QAlxoiVf9U8SfNelrz/ONJA/bMvWr/JnOGA9PPmFD5Uc"
      + "+kV/q+i94dEMjsC5CQ1moUHWSP2xlQhbOzBP2+oPXw3z2fBs9XJgnTH6QWMAAvLs"
      + "3ug9po0loNHLobT/D/XdXvcrb3wvwvPT2FptZqrtonH/OdzT9JdfrA==");

  private static final byte[] PRIVATE_KEY = Base64.decode(""
      + "lQH8BD89pyQBBADk1aljL6mBOvd6k4Myr/0yaSI94SPC5WDwuptXZNM92wy8FVZP"
      + "RRQAfglkvEXRTlrfxRt7RL9p83KDXUb47/VgC8iBjWsLWnuDJeqAE9Ov+ddclM1x"
      + "zpPvcSt8JFzeY3c1IX+HANqBqS0lf6WZaHLCAy/owlELbplD8BaHZkh4cwAGKf4D"
      + "AwKbLeIOVYTEdWD5v/YgW8ERs0pDsSIfBTvsJp2qA798KeFuED6jGsHUzdi1M990"
      + "6PRtplQgnoYmYQrzEc6DXAiAtBR4Kuxi4XHx0ZR2wpVlVxm2Ypgz7pbBNWcWqzvw"
      + "33inl7tR4IDsRdJOY8cFlN+1tSCf16sDidtKXUVjRjZNYJytH18VfSPlGXMeYgtw"
      + "3cSGNTERwKaq5E/SozT2MKTiORO0g0Mtyz+9MEB6XVXFavMun/mXURqbZN/k9BFb"
      + "z+TadpkihrLD1xw3Hp+tpe4CwPQ2GdWKI9KNo5gEnbkJgLrSMGgWalPhknlNHRyY"
      + "bSq6lbIMJEE3LoOwvYWwweR1+GrV9farJESdunl1mDr5/d6rKru+FFDwZM3na1IF"
      + "4Ei4FpqhivZ4zG6pN5XqLy+AK85EiW4XH0yAKX1O4YlbmDU4BjxhiwTdwuVMCjLO"
      + "5++jkz5BBQWdFX8CCMA4FJl36G70IbGzuFfOj07ly7QvRXJpYyBFY2hpZG5hICh0"
      + "ZXN0IGtleSkgPGVyaWNAYm91bmN5Y2FzdGxlLm9yZz6IuAQTAQIAIgUCPz2nJAIb"
      + "AwUJAIPWAAQLBwMCAxUCAwMWAgECHgECF4AACgkQNVhhRv3z83PFjAP/QW47gfBO"
      + "PEAJcaIlX/VPEnzXpa8/zjSQP2zL1q/yZzhgPTz5hQ+VHPpFf6voveHRDI7AuQkN"
      + "ZqFB1kj9sZUIWzswT9vqD18N89nwbPVyYJ0x+kFjAALy7N7oPaaNJaDRy6G0/w/1"
      + "3V73K298L8Lz09habWaq7aJx/znc0/SXX6w=");
}
