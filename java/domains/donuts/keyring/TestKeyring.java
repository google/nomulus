// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package domains.donuts.keyring;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.VerifyException;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.concurrent.Immutable;

import google.registry.keyring.api.Keyring;
import google.registry.keyring.api.PgpHelper;
import google.registry.keyring.api.PgpHelper.KeyRequirement;

/** {@link Keyring} that uses a single test PGP key for all signing and encryption. */
@Immutable
public final class TestKeyring implements Keyring {

  /*
   * PGP key ID
   */
  private static final String TEST_KEY_ID = "mercury-donuts-test@example.test";

  private static final ByteSource PGP_PUBLIC_KEYRING =
      Resources.asByteSource(Resources.getResource(TestKeyring.class, "test-public-keyring.asc"));

  private static final ByteSource PGP_PRIVATE_KEYRING =
      Resources.asByteSource(Resources.getResource(TestKeyring.class, "test-private-keyring.asc"));

  private static final ByteSource SSH_RDE_PUBLIC_KEY =
      Resources.asByteSource(Resources.getResource(TestKeyring.class, "test-ssh_rsa.pub.asc"));

  private static final ByteSource SSH_RDE_PRIVATE_KEY =
      Resources.asByteSource(Resources.getResource(TestKeyring.class, "test-ssh_rsa.asc"));

  private final PGPPublicKeyRingCollection publics;
  private final PGPSecretKeyRingCollection privates;

  public TestKeyring() {
    try (InputStream publicInput = PGP_PUBLIC_KEYRING.openStream();
        InputStream privateInput = PGP_PRIVATE_KEYRING.openStream()) {
      publics = new BcPGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicInput));
      privates = new BcPGPSecretKeyRingCollection(PGPUtil.getDecoderStream(privateInput));
    } catch (PGPException | IOException e) {
      throw new VerifyException("Failed to load PGP keyrings from jar", e);
    }
  }

  @Override
  public PGPKeyPair getRdeSigningKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.SIGN);
  }

  @Override
  public PGPKeyPair getBrdaSigningKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.SIGN);
  }

  @Override
  public PGPPublicKey getRdeStagingEncryptionKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.ENCRYPT).getPublicKey();
  }

  @Override
  public PGPPrivateKey getRdeStagingDecryptionKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.ENCRYPT).getPrivateKey();
  }

  @Override
  public PGPPublicKey getRdeReceiverKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.ENCRYPT).getPublicKey();
  }

  @Override
  public PGPPublicKey getBrdaReceiverKey() {
    return PgpHelper.lookupKeyPair(publics, privates, TEST_KEY_ID, KeyRequirement.ENCRYPT).getPublicKey();
  }

  @Override
  public String getRdeSshClientPublicKey() {
    try {
      return new String(SSH_RDE_PUBLIC_KEY.read(), UTF_8);
    } catch (IOException e) {
      throw new VerifyException("failed to load ssh key", e);
    }
  }

  @Override
  public String getRdeSshClientPrivateKey() {
    try {
      return new String(SSH_RDE_PRIVATE_KEY.read(), UTF_8);
    } catch (IOException e) {
      throw new VerifyException("failed to load ssh key", e);
    }
  }

  @Override
  public String getIcannReportingPassword() {
    return "icann-password";
  }

  @Override
  public String getMarksdbDnlLogin() {
    return "dnl-login";
  }

  @Override
  public String getMarksdbLordnPassword() {
    return "lordn-password";
  }

  @Override
  public String getMarksdbSmdrlLogin() {
    return "smdrl-login";
  }

  /** @throws UnsupportedOperationException always */
  @Override
  public String getJsonCredential() {
    throw new UnsupportedOperationException("not implemented");
  }

  /** @throws UnsupportedOperationException always */
  @Override
  public String getBraintreePrivateKey() {
    throw new UnsupportedOperationException("not implemented");
  }

  /** Does nothing. */
  @Override
  public void close() {}
}
