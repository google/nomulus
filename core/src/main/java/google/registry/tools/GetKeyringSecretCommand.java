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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.keyring.api.KeySerializer;
import google.registry.keyring.api.Keyring;
import google.registry.tools.params.KeyringKeyName;
import google.registry.tools.params.PathParameter;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.Security;
import javax.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;

/** Retrieves ASCII-armored secrets from the active {@link Keyring} implementation. */
@Parameters(
  separators = " =",
  commandDescription = "Retrieves the value of a secret from the keyring."
)
final class GetKeyringSecretCommand implements CommandWithRemoteApi {

  @Inject Keyring keyring;

  @Inject
  GetKeyringSecretCommand() {}

  @Parameter(names = "--keyname", description = "The secret to load", required = true)
  private KeyringKeyName keyringKeyName;

  @Parameter(
    names = {"-o", "--output"},
    description = "Name of output file for key data.",
    validateWith = PathParameter.OutputFile.class
  )
  private Path outputPath = null;

  @Override
  public void run() throws Exception {
    OutputStream out = outputPath != null ? new FileOutputStream(outputPath.toFile()) : System.out;
    Security.addProvider(new BouncyCastleProvider());

    switch (keyringKeyName) {
      case BRDA_RECEIVER_PUBLIC_KEY:
        out.write(KeySerializer.serializePublicKey(keyring.getBrdaReceiverKey()));
        break;
      case BRDA_SIGNING_KEY_PAIR:
        out.write(KeySerializer.serializeKeyPair(keyring.getBrdaSigningKey()));
        break;
      case BRDA_SIGNING_PUBLIC_KEY:
        out.write(KeySerializer.serializePublicKey(keyring.getBrdaSigningKey().getPublicKey()));
        break;
      case ICANN_REPORTING_PASSWORD:
        out.write(KeySerializer.serializeString(keyring.getIcannReportingPassword()));
        break;
      case SAFE_BROWSING_API_KEY:
        out.write(KeySerializer.serializeString(keyring.getSafeBrowsingAPIKey()));
        break;
      case JSON_CREDENTIAL:
        out.write(KeySerializer.serializeString(keyring.getJsonCredential()));
        break;
      case MARKSDB_DNL_LOGIN_AND_PASSWORD:
        out.write(KeySerializer.serializeString(keyring.getMarksdbDnlLoginAndPassword()));
        break;
      case MARKSDB_LORDN_PASSWORD:
        out.write(KeySerializer.serializeString(keyring.getMarksdbLordnPassword()));
        break;
      case MARKSDB_SMDRL_LOGIN_AND_PASSWORD:
        out.write(KeySerializer.serializeString(keyring.getMarksdbSmdrlLoginAndPassword()));
        break;
      case RDE_RECEIVER_PUBLIC_KEY:
        out.write(KeySerializer.serializePublicKey(keyring.getRdeReceiverKey()));
        break;
      case RDE_SIGNING_KEY_PAIR:
        out.write(KeySerializer.serializeKeyPair(keyring.getRdeSigningKey()));
        break;
      case RDE_SIGNING_PUBLIC_KEY:
        out.write(KeySerializer.serializePublicKey(keyring.getRdeSigningKey().getPublicKey()));
        break;
      case RDE_SSH_CLIENT_PRIVATE_KEY:
        out.write(KeySerializer.serializeString(keyring.getRdeSshClientPrivateKey()));
        break;
      case RDE_SSH_CLIENT_PUBLIC_KEY:
        out.write(KeySerializer.serializeString(keyring.getRdeSshClientPublicKey()));
        break;
      case RDE_STAGING_KEY_PAIR:
        // Note that we're saving a key pair rather than just the private key because we can't
        // serialize a private key on its own. See {@link KeySerializer}.
        out.write(KeySerializer.serializeKeyPair(
            new PGPKeyPair(
                keyring.getRdeStagingEncryptionKey(), keyring.getRdeStagingDecryptionKey())));
        break;
      case RDE_STAGING_PUBLIC_KEY:
        out.write(KeySerializer.serializePublicKey(keyring.getRdeStagingEncryptionKey()));
        break;
    }
  }
}
