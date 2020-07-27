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

package google.registry.keyring.api;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.testing.BouncyCastleProviderExtension;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link KeySerializer}. */
class KeySerializerTest {

  @RegisterExtension
  final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  /**
   * An Armored representation of a pgp secret key.
   *
   * <p>This key was created specifically for tests. It has a password of "12345678".
   *
   * <p>Created using 'gpg --gen-key' followed by 'gpg --export-secret-key -a'.
   */
  private static final String ARMORED_KEY_STRING =
      "-----BEGIN PGP PRIVATE KEY BLOCK-----\n"
          + "Version: GnuPG v1\n"
          + "\n"
          + "lQO+BFjbx+IBCADO3hs5CE2fIorcq8+yBdnVb1+pyPrm/48vIHCIhFHeqBjmxuY9\n"
          + "cdgRjiNTXp8Rs1N4e1cXWtiA2vuoBmkLF1REckgyPyocn/ssta/pRHd4fqskrQwJ\n"
          + "CoIoQaP3bd+KSUJoxTSckWgXFQnoNWj1kg5gv8WnY7nWB0jtjwyGSQoHy/pK3h94\n"
          + "GDrHg5CQBVZ3N6NBob9bGoEhi53gh/1UIUS8GVSLS9Qwt26+3oJ1RlGl7PQoDjeK\n"
          + "oraY8i0sl9rD06qVTFtmXLHHogP4WgD6GItTam7flPqXXNzl0PUk0mTcv9cwt4VP\n"
          + "WZ5YJ7y5CqNhwqU9YeirbooHq6T9+nHxU2ddABEBAAH+AwMCjPetoiDH5m5gRgn4\n"
          + "FB3io2zclIcCEnvfby1VZ/82u2nZXtSK9N5twf6pWH4KD1/3VkgqlEBhrQkqz8v4\n"
          + "C5AWObWT1lF1hQkh/O6OFTPN7DMUSqLX/z6qXv7c5fMFU69CGq6B64s/SMKxfI1p\n"
          + "roDoFA912GlVH89ZT8BDtTahQQzJyHL/61KZVMidLt6IqeWsI3XCy1u8/WkLYkBG\n"
          + "dqvCPBf2mlVeEOwmoAPWTMvV1lHlvauu4ofLJh8VYoO+wziX86yiQ37tsPN8Jspx\n"
          + "gpaiSH6f2x+I7vXFKO2ThqC8jNjfQFLnQ9yWtDYVtLgA6POMLFNOb9c733lzvHPU\n"
          + "UgQRCumAyTeX0wLoC2rEG3Qu/o+Sbm10BNQmkNBxH+Pkdk+ubO/4lvaY8+nxWw1t\n"
          + "sIzoUoln1dHo5lbJsA/++ttNZsAMPlGB5nDC/EmhUHjKDhRpj9OwX+aFxAbBpJXg\n"
          + "BKBirm7VnWp+rUd5YlwKDOJFLlNKgFmh8XBTTpe9DE/qKvnABlFjdhXpUXYEMfHw\n"
          + "D7mS1J3gtd2Iz24pwbL52XA+M5nIWnX0A9N4pi/k+3M02T6up/qcqArjf/CFFZS1\n"
          + "CSng1xYPBrxDJyIXTsFFQ7kEJrSUyvXylsLHVeXZmnwqmmjh8RFohTWecDSHgxi6\n"
          + "R4m9ZHVagWxecDvNmxY5vPRzhNmP5w/teCMVnEHH5VXktBmbn8TW3hLaFHs2H88b\n"
          + "xovFXrn1pQ7hg6KLURbhXsBQlL/3NXFSXYc5cimP4lewnd6sqlnSfY/o9JujgoAV\n"
          + "v1Wo63Jjl9fhlu8Vr/sHrAPWQS9mWzMUJy6EcTJRop39J90fPqcsz7Iz4gdH8QNY\n"
          + "ve/iLjIuGbkK4KevptD7oR4zUIwKUpIKVJTr1Q2ukKU0pAVCyn19nRAR1RGri81L\n"
          + "jbRAR1RMRCBUZXN0ZXIgKFRoaXMga2V5IGlzIHVzZWQgZm9yIHRlc3RzIG9ubHkp\n"
          + "IDx0ZXN0ZXJAdGVzdC50ZXN0PokBOAQTAQIAIgUCWNvH4gIbAwYLCQgHAwIGFQgC\n"
          + "CQoLBBYCAwECHgECF4AACgkQxCHL9+c/Gv2LKgf+KdUPUlKq0N3oSzXk5RcXJZDT\n"
          + "v8teeLXyu3loaXzEuK89/V5BdDL5nRu8+GUhfFRkd/LFv0rwehcJZ5TtXghukk6r\n"
          + "5kvekPf8vVjgMO89RrmiW2oKqqmhP8VZd1T1vQacQ4J6eNKCjbuLym47m4Vp9VZm\n"
          + "lHNGNgkDbU1zVxhF23rLqOqzltiIcyCafMPFJNRUANlLkEIvAo8dGziqn5l2X71w\n"
          + "9KQw4LzQHrR1ulm0h4SbYwhQQ2Qz1FzfVjyuRUjye5QJxLEa9M92l1oLMjubt3Qx\n"
          + "QEiL05Bp3uYd+S97BuxbDFz+hNUHfIaSVuwrVgdz2tcwBTyBl2jUTukKC2nMfJ0D\n"
          + "vgRY28fiAQgA3qW5JTZNG26Iv9k+cysl5TWeAO71yrEAL4WAXoKQYIEVMcCi3Rt9\n"
          + "YW+FhZ+z056n3EZsgIYsPniipdyRBrehQI5f9CRFcyqkMu8tuBIqsJaZAhcMcYoN\n"
          + "zNWqk7mK5Wjp4yfQAEkyb13YXg+zFEtBiEOG5FPonA+vGIFeOUKSR0s+hoxhcBvS\n"
          + "Q5BGXlHP20UQBdLtPfnOx+scI9sjYBtO13ZaURBnsHfHSyDnNa5HZD6eTk7V8bjy\n"
          + "ScUDHOW/Ujva3yH/7KQeR42cdba38zpvSlivwIpnGtNlhABR+mZhee5BwxGNusJ9\n"
          + "D/Xi8dSSjpwZH8b6fHhwoSpb5AK6tvGgHwARAQAB/gMDAoz3raIgx+ZuYJS4j+fX\n"
          + "6PmrHg+nOoes3i2RufCvjMhRSMU+aZo1e8fNWP9NnVKPna9Ya2PHGkHHUZlx6CE9\n"
          + "EPvMwl9d8web5vBSCkzNwtUtgCrShk7cyDbHq7dLzbqxZTZK+HKX6CGL3dAC1Z1J\n"
          + "zvgpj6enpTZSKSbxkPSGcxlXkT/hm7x+wYXsPgbMGH/rRnHJ1Ycg4nlHxq7sPgHK\n"
          + "Jgfl3a4WuyN6Ja1mVPPYxSSyKusFdXZHreqR+GLwGc2PsKjQy870uJPF4zBGYya1\n"
          + "iJ/8o5xJ1OxLa+SrvPExgkFmt271SHFAYk0Xx/IUshZZVP+3i8HHo7yMKEANxM+n\n"
          + "Mcr9MW963Dm8thbxy3yC2GvufYz13yJJeWnMel/enSDvSieUAFsEH4NalE7HX7Mv\n"
          + "NoBx6wuQTFVdojauXrERD75vYGamMC1/0h+6rzejE4HP0iDHlujJmkucAD2K8ibb\n"
          + "ax4QiRJtatel48wYqjIkhZ8x6mFaUiBL34iyh4t0vY0CgZOsEIegy1pRkoO8u72T\n"
          + "rcgFHhHQgtf6OaPG4QnSWbxrftRmZe7W4K5tyrmoLHjsm6exAFcTmpl79qE8Mn+7\n"
          + "jTspdKeTkhse5K/7ct749kZDD8FwVhSP9vqfbDhwmdmCQNp5rRQKm1+YBnamlnuz\n"
          + "IEWzxmQ2NqjEeV65bk7BfnbHYe42ZNNIzE4XahzrBVwhtMaLGLz/7YF4Czwrbn0D\n"
          + "KQwjp5qbjDAO+0FCOxN4xFItazp0bKlHnGYflEPLbFoeplBfi2sZfmQ6PUmA3UPr\n"
          + "T96e6fHBsctYVa4JP0HWKGcwYIhih9uD53UFsg0BJW5iXsGfMzuEo2TUXBD5qFUN\n"
          + "xrS5Nt/Ra1psaaZxXDeiHWM5qlmk37xoFjnPV5RV0014TqNr//VHgJknWNuhcMqJ\n"
          + "AR8EGAECAAkFAljbx+ICGwwACgkQxCHL9+c/Gv1WzQf+Ihv0zeOFOZdvI6xOTVXS\n"
          + "qBg6k1aMdbwqshaHEvLhAY00XQmhPr65ymEJVaWRloviQ76dN9k4tTi6lYX/CGob\n"
          + "bi3fUNDNQGKdyvhoxtneKsXw/3LfFh7JphVWQizh/yJHsKFvzmmMpnC4WTJ3NBTe\n"
          + "G+CcHlGdFYuxc4+Z9nZG2jOorQtlFGLEqLzdM8+OJ8KyOqvaOpa0vaMyvN40QiGv\n"
          + "raEbRpkDbxJiPp4RuPiu7S8KwKpmjgmuAXaoYKcrL8KIt9WvYWQirW8oZcbP3g/1\n"
          + "laUCBMklv75toeXKeYi5Y74CvnPuciCKsNtm0fZkmhfE1vpPFn4/UqRmDtPrToVQ\n"
          + "GQ==\n"
          + "=qeFB\n"
          + "-----END PGP PRIVATE KEY BLOCK-----\n";

  private static final BcPGPSecretKeyRing SECRET_KEYRING = getSecretKeyring();

  private static final PGPSecretKey SECRET_KEY = SECRET_KEYRING.getSecretKey();

  private static final PGPPublicKey PUBLIC_KEY = SECRET_KEY.getPublicKey();

  private static final PGPPrivateKey PRIVATE_KEY = extractPrivateKey(SECRET_KEY, "12345678");

  // Used for static initialization only.
  private static BcPGPSecretKeyRing getSecretKeyring() {
    try {
      return new BcPGPSecretKeyRing(
          PGPUtil.getDecoderStream(new ByteArrayInputStream(ARMORED_KEY_STRING.getBytes(UTF_8))));
    } catch (IOException | PGPException e) {
      throw new Error(e);
    }
  }

  // Used for static initialization only.
  private static PGPPrivateKey extractPrivateKey(PGPSecretKey secretKey, String password) {
    try {
      return secretKey.extractPrivateKey(
          new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
              .build(password.toCharArray()));
    } catch (PGPException e) {
      throw new Error(e);
    }
  }

  @Test
  void serializeString() {
    String result = KeySerializer.deserializeString(KeySerializer.serializeString("value\n"));
    assertThat(result).isEqualTo("value\n");
  }

  @Test
  void serializePublicKey() throws Exception {
    PGPPublicKey publicKeyResult =
        KeySerializer.deserializePublicKey(KeySerializer.serializePublicKey(PUBLIC_KEY));

    assertThat(publicKeyResult.getEncoded()).isEqualTo(PUBLIC_KEY.getEncoded());
  }

  @Test
  void serializeKeyPair() throws Exception {
    PGPKeyPair keyPairResult =
        KeySerializer.deserializeKeyPair(
            KeySerializer.serializeKeyPair(new PGPKeyPair(PUBLIC_KEY, PRIVATE_KEY)));

    assertThat(keyPairResult.getPublicKey().getEncoded()).isEqualTo(PUBLIC_KEY.getEncoded());
    assertThat(keyPairResult.getPrivateKey().getKeyID()).isEqualTo(PRIVATE_KEY.getKeyID());
    assertThat(keyPairResult.getPrivateKey().getPrivateKeyDataPacket().getEncoded())
        .isEqualTo(PRIVATE_KEY.getPrivateKeyDataPacket().getEncoded());
    assertThat(keyPairResult.getPrivateKey().getPublicKeyPacket().getEncoded())
        .isEqualTo(PRIVATE_KEY.getPublicKeyPacket().getEncoded());
  }
}
