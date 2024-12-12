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

import static com.google.common.io.Resources.getResource;
import static google.registry.keyring.api.PgpHelper.KeyRequirement.ENCRYPT_SIGN;
import static google.registry.keyring.api.PgpHelper.lookupKeyPair;

import com.google.common.base.VerifyException;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.concurrent.Immutable;
import javax.inject.Named;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection;

/**
 * Dagger keyring module that provides an {@link InMemoryKeyring} instance populated with dummy
 * values.
 *
 * <p>This dummy module allows the domain registry code to compile and run in an unmodified state,
 * with all attempted outgoing connections failing because the supplied dummy credentials aren't
 * valid. For a real system that needs to connect with external services, you should replace this
 * module with one that loads real credentials from secure sources.
 *
 * <p>The dummy PGP keyrings are created using gnupg1/pgp1 roughly like the following (using
 * gnupg2/pgp2 is an exercise left for the developer):
 *
 * <pre>{@code
 * # mkdir gpg
 * # chmod 700 gpg
 * # gpg1 --homedir gpg --gen-key <<<EOF
 * 1
 * 1024
 * 0
 * Y
 * Test Registry
 * test-registry@example.com
 *
 * O
 * EOF
 * [press enter twice at keyring password prompts]
 * # gpg1 --homedir gpg -a -o pgp-public-keyring.asc --export test-registry@example.com
 * # gpg1 --homedir gpg -a -o pgp-private-keyring.asc --export-secret-keys test-registry@example.com
 * # mv pgp*keyring.asc java/google/registry/keyring/api
 * # rm -rf gpg
 * }</pre>
 */
@Module
@Immutable
public abstract class DummyKeyringModule {

  public static final String NAME = "Dummy";

  /** The contents of a dummy PGP public key stored in a file. */
  private static final ByteSource PGP_PUBLIC_KEYRING =
      Resources.asByteSource(getResource(InMemoryKeyring.class, "pgp-public-keyring.asc"));

  /** The contents of a dummy PGP private key stored in a file. */
  private static final ByteSource PGP_PRIVATE_KEYRING =
      Resources.asByteSource(getResource(InMemoryKeyring.class, "pgp-private-keyring.asc"));

  /** The email address of the aforementioned PGP key. */
  private static final String EMAIL_ADDRESS = "test-registry@example.com";

  @Binds
  @IntoMap
  @StringKey(NAME)
  abstract Keyring provideKeyring(@Named("DummyKeyring") InMemoryKeyring keyring);

  /** Always returns a {@link InMemoryKeyring} instance. */
  @Provides
  @Named("DummyKeyring")
  static InMemoryKeyring provideDummyKeyring() {
    PGPKeyPair dummyKey;
    try (InputStream publicInput = PGP_PUBLIC_KEYRING.openStream();
        InputStream privateInput = PGP_PRIVATE_KEYRING.openStream()) {
      PGPPublicKeyRingCollection publicKeys =
          new BcPGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicInput));
      PGPSecretKeyRingCollection privateKeys =
          new BcPGPSecretKeyRingCollection(PGPUtil.getDecoderStream(privateInput));
      dummyKey = lookupKeyPair(publicKeys, privateKeys, EMAIL_ADDRESS, ENCRYPT_SIGN);
    } catch (PGPException | IOException e) {
      throw new VerifyException("Failed to load PGP keys from jar", e);
    }
    // Use the same dummy PGP keypair for all required PGP keys -- a real production system would
    // have different values for these keys.  Pass dummy values for all Strings.
    return new InMemoryKeyring(
        dummyKey,
        dummyKey,
        dummyKey.getPublicKey(),
        dummyKey,
        dummyKey.getPublicKey(),
        "not a real key",
        "not a real key",
        "not a real password",
        "not a real API key",
        "not a real login",
        "not a real password",
        "not a real login",
        "not a real credential",
        "not a real password",
        "not a real password",
        "not the real primary connection",
        "not the real replica connection");
  }

  private DummyKeyringModule() {}
}
