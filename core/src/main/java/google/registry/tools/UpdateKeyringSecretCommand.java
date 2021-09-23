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

import static google.registry.keyring.api.KeySerializer.deserializeKeyPair;
import static google.registry.keyring.api.KeySerializer.deserializePublicKey;
import static google.registry.keyring.api.KeySerializer.deserializeString;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.keyring.kms.KmsUpdater;
import google.registry.tools.params.KeyringKeyName;
import google.registry.tools.params.PathParameter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;

/**
 * Command to set and update ASCII-armored secret from the active {@code Keyring} implementation.
 */
@Parameters(separators = " =", commandDescription = "Update values of secret in the keyring.")
final class UpdateKeyringSecretCommand implements CommandWithRemoteApi {

  @Inject KmsUpdater kmsUpdater;

  @Inject
  UpdateKeyringSecretCommand() {}

  @Parameter(names  = "--keyname", description = "The secret to update", required = true)
  private KeyringKeyName keyringKeyName;

  @Parameter(
    names = {"--input"},
    description =
        "Name of input file for key data.",
    validateWith = PathParameter.InputFile.class
  )
  private Path inputPath = null;

  @Override
  public void run() throws Exception {
    byte[] input = Files.readAllBytes(inputPath);

    switch (keyringKeyName) {
      case BRDA_RECEIVER_PUBLIC_KEY:
        kmsUpdater.setBrdaReceiverPublicKey(deserializePublicKey(input));
        break;
      case BRDA_SIGNING_KEY_PAIR:
        kmsUpdater.setBrdaSigningKey(deserializeKeyPair(input));
        break;
      case BRDA_SIGNING_PUBLIC_KEY:
        throw new IllegalArgumentException(
            "Can't update BRDA_SIGNING_PUBLIC_KEY directly."
            + " Must update public and private keys together using BRDA_SIGNING_KEY_PAIR.");
      case ICANN_REPORTING_PASSWORD:
        kmsUpdater.setIcannReportingPassword(deserializeString(input));
        break;
      case JSON_CREDENTIAL:
        kmsUpdater.setJsonCredential(deserializeString(input));
        break;
      case MARKSDB_DNL_LOGIN_AND_PASSWORD:
        kmsUpdater.setMarksdbDnlLoginAndPassword(deserializeString(input));
        break;
      case MARKSDB_LORDN_PASSWORD:
        kmsUpdater.setMarksdbLordnPassword(deserializeString(input));
        break;
      case MARKSDB_SMDRL_LOGIN_AND_PASSWORD:
        kmsUpdater.setMarksdbSmdrlLoginAndPassword(deserializeString(input));
        break;
      case RDE_RECEIVER_PUBLIC_KEY:
        kmsUpdater.setRdeReceiverPublicKey(deserializePublicKey(input));
        break;
      case RDE_SIGNING_KEY_PAIR:
        kmsUpdater.setRdeSigningKey(deserializeKeyPair(input));
        break;
      case RDE_SIGNING_PUBLIC_KEY:
        throw new IllegalArgumentException(
            "Can't update RDE_SIGNING_PUBLIC_KEY directly."
            + " Must update public and private keys together using RDE_SIGNING_KEY_PAIR.");
      // Note that RDE_SSH_CLIENT public / private keys are slightly different than other key pairs,
      // since they are just regular strings rather than {@link PGPKeyPair}s (because OpenSSH
      // doesn't use PGP-style keys)
      //
      // Hence we can and need to update the private and public keys individually.
      case RDE_SSH_CLIENT_PRIVATE_KEY:
        kmsUpdater.setRdeSshClientPrivateKey(deserializeString(input));
        break;
      case RDE_SSH_CLIENT_PUBLIC_KEY:
        kmsUpdater.setRdeSshClientPublicKey(deserializeString(input));
        break;
      case RDE_STAGING_KEY_PAIR:
        kmsUpdater.setRdeStagingKey(deserializeKeyPair(input));
        break;
      case SAFE_BROWSING_API_KEY:
        kmsUpdater.setSafeBrowsingAPIKey(deserializeString(input));
        break;
      case RDE_STAGING_PUBLIC_KEY:
        throw new IllegalArgumentException(
            "Can't update RDE_STAGING_PUBLIC_KEY directly."
            + " Must update public and private keys together using RDE_STAGING_KEY_PAIR.");
    }

    kmsUpdater.update();
  }
}

