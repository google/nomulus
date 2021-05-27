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

package google.registry.keyring.kms;

import static com.google.common.io.Resources.getResource;

import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;

/** Stores dummy values for test use in {@link KmsUpdaterTest}. */
final class KmsTestHelper {

  static final String DUMMY_CRYPTO_KEY_VERSION = "cheeseburger";
  static final String DUMMY_ENCRYPTED_VALUE = "meow";

  /** The contents of a dummy PGP private key stored in a file. */
  private static final ByteSource PGP_PRIVATE_KEYRING =
      Resources.asByteSource(getResource(KmsTestHelper.class, "pgp-private-keyring-registry.asc"));

  private static BcPGPSecretKeyRing getPrivateKeyring() throws Exception {
    return new BcPGPSecretKeyRing(PGPUtil.getDecoderStream(PGP_PRIVATE_KEYRING.openStream()));
  }

  static PGPPublicKey getPublicKey() throws Exception {
    return getPrivateKeyring().getPublicKey();
  }

  static PGPKeyPair getKeyPair() throws Exception {
    PGPSecretKey secretKey = getPrivateKeyring().getSecretKey();
    return new PGPKeyPair(
        secretKey.getPublicKey(),
        secretKey.extractPrivateKey(
            new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
            .build(new char[0])));
  }

  private KmsTestHelper() {}
}
