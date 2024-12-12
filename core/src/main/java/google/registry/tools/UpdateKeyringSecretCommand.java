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
import google.registry.keyring.secretmanager.SecretManagerKeyringUpdater;
import google.registry.tools.params.KeyringKeyName;
import google.registry.tools.params.PathParameter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;

/**
 * Command to set and update ASCII-armored secret from the active {@code Keyring} implementation.
 */
@Parameters(separators = " =", commandDescription = "Update values of secret in the keyring.")
final class UpdateKeyringSecretCommand implements Command {

  @Inject SecretManagerKeyringUpdater secretManagerKeyringUpdater;

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
      case BRDA_RECEIVER_PUBLIC_KEY ->
          secretManagerKeyringUpdater.setBrdaReceiverPublicKey(deserializePublicKey(input));
      case BRDA_SIGNING_KEY_PAIR ->
          secretManagerKeyringUpdater.setBrdaSigningKey(deserializeKeyPair(input));
      case BRDA_SIGNING_PUBLIC_KEY ->
          throw new IllegalArgumentException(
              "Can't update BRDA_SIGNING_PUBLIC_KEY directly."
                  + " Must update public and private keys together using BRDA_SIGNING_KEY_PAIR.");
      case BSA_API_KEY -> secretManagerKeyringUpdater.setBsaApiKey(deserializeString(input));
      case ICANN_REPORTING_PASSWORD ->
          secretManagerKeyringUpdater.setIcannReportingPassword(deserializeString(input));
      case MARKSDB_DNL_LOGIN_AND_PASSWORD ->
          secretManagerKeyringUpdater.setMarksdbDnlLoginAndPassword(deserializeString(input));
      case MARKSDB_LORDN_PASSWORD ->
          secretManagerKeyringUpdater.setMarksdbLordnPassword(deserializeString(input));
      case MARKSDB_SMDRL_LOGIN_AND_PASSWORD ->
          secretManagerKeyringUpdater.setMarksdbSmdrlLoginAndPassword(deserializeString(input));
      case RDE_RECEIVER_PUBLIC_KEY ->
          secretManagerKeyringUpdater.setRdeReceiverPublicKey(deserializePublicKey(input));
      case RDE_SIGNING_KEY_PAIR ->
          secretManagerKeyringUpdater.setRdeSigningKey(deserializeKeyPair(input));
      case RDE_SIGNING_PUBLIC_KEY ->
          throw new IllegalArgumentException(
              "Can't update RDE_SIGNING_PUBLIC_KEY directly."
                  + " Must update public and private keys together using RDE_SIGNING_KEY_PAIR.");
        // Note that RDE_SSH_CLIENT public / private keys are slightly different than other key
        // pairs, since they are just regular strings rather than {@link PGPKeyPair}s (because
        // OpenSSH doesn't use PGP-style keys)
        //
        // Hence we can and need to update the private and public keys individually.
      case RDE_SSH_CLIENT_PRIVATE_KEY ->
          secretManagerKeyringUpdater.setRdeSshClientPrivateKey(deserializeString(input));
      case RDE_SSH_CLIENT_PUBLIC_KEY ->
          secretManagerKeyringUpdater.setRdeSshClientPublicKey(deserializeString(input));
      case RDE_STAGING_KEY_PAIR ->
          secretManagerKeyringUpdater.setRdeStagingKey(deserializeKeyPair(input));
      case RDE_STAGING_PUBLIC_KEY ->
          throw new IllegalArgumentException(
              "Can't update RDE_STAGING_PUBLIC_KEY directly."
                  + " Must update public and private keys together using RDE_STAGING_KEY_PAIR.");
      case SAFE_BROWSING_API_KEY ->
          secretManagerKeyringUpdater.setSafeBrowsingAPIKey(deserializeString(input));
      case SQL_PRIMARY_CONN_NAME ->
          secretManagerKeyringUpdater.setSqlPrimaryConnectionName(deserializeString(input));
      case SQL_REPLICA_CONN_NAME ->
          secretManagerKeyringUpdater.setSqlReplicaConnectionName(deserializeString(input));
    }

    secretManagerKeyringUpdater.update();
  }
}

