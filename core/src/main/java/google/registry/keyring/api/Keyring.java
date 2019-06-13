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

import javax.annotation.concurrent.ThreadSafe;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * Nomulus keyring interface.
 *
 * <p>Separate methods are defined for each specific situation in which the registry server needs a
 * secret value, like a PGP key or password.
 */
@ThreadSafe
public interface Keyring extends AutoCloseable {

  /**
   * Returns the key which should be used to sign RDE deposits being uploaded to a third-party.
   *
   * <p>When we give all our data to the escrow provider, they'll need a signature to ensure the
   * data is authentic.
   *
   * <p>This keypair should only be known to the domain registry shared registry system.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  PGPKeyPair getRdeSigningKey();

  /**
   * Returns public key for encrypting escrow deposits being staged to cloud storage.
   *
   * <p>This adds an additional layer of security so cloud storage administrators won't be tempted
   * to go poking around the App Engine Cloud Console and see a dump of the entire database.
   *
   * <p>This keypair should only be known to the domain registry shared registry system.
   *
   * @see #getRdeStagingDecryptionKey()
   */
  PGPPublicKey getRdeStagingEncryptionKey();

  /**
   * Returns private key for decrypting escrow deposits retrieved from cloud storage.
   *
   * <p>This method may impose restrictions on who can call it. For example, we'd want to check that
   * the caller isn't an HTTP request attacking a vulnerability in the admin console. The request
   * should originate from a backend task queue servlet invocation of the RDE upload thing.
   *
   * @see #getRdeStagingEncryptionKey()
   * @see google.registry.rde.RdeUploadAction
   */
  PGPPrivateKey getRdeStagingDecryptionKey();

  /**
   * Returns public key of escrow agent for encrypting deposits as they're uploaded.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  PGPPublicKey getRdeReceiverKey();

  /**
   * Returns the PGP key we use to sign Bulk Registration Data Access (BRDA) deposits.
   *
   * @see google.registry.rde.BrdaCopyAction
   */
  PGPKeyPair getBrdaSigningKey();

  /**
   * Returns public key of receiver of Bulk Registration Data Access (BRDA) deposits.
   *
   * @see google.registry.rde.BrdaCopyAction
   */
  PGPPublicKey getBrdaReceiverKey();

  /**
   * Returns public key for SSH client connections made by RDE.
   *
   * <p>This is a string containing what would otherwise be the contents of an {@code
   * ~/.ssh/id_rsa.pub} file. It's usually a single line with the name of the algorithm, the base64
   * key, and the email address of the owner.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  String getRdeSshClientPublicKey();

  /**
   * Returns private key for SSH client connections made by RDE.
   *
   * <p>This is a string containing what would otherwise be the contents of an {@code ~/.ssh/id_rsa}
   * file. It's ASCII-armored text.
   *
   * <p>This method may impose restrictions on who can call it. For example, we'd want to check that
   * the caller isn't an HTTP request attacking a vulnerability in the admin console. The request
   * should originate from a backend task queue servlet invocation of the RDE upload thing.
   *
   * @see google.registry.rde.RdeUploadAction
   */
  String getRdeSshClientPrivateKey();

  /**
   * Returns the API key for accessing the SafeBrowsing API.
   *
   * @see google.registry.reporting.spec11.GenerateSpec11ReportAction
   */
  String getSafeBrowsingAPIKey();

  /**
   * Returns password to be used when uploading reports to ICANN.
   *
   * @see google.registry.rde.RdeReportAction
   */
  String getIcannReportingPassword();

  /**
   * Returns {@code user:password} login for TMCH MarksDB HTTP server DNL interface.
   *
   * @see google.registry.tmch.TmchDnlAction
   */
  String getMarksdbDnlLoginAndPassword();

  /**
   * Returns password for TMCH MarksDB HTTP server LORDN interface.
   *
   * @see "google.registry.tmch.LordnRequestInitializer"
   */
  String getMarksdbLordnPassword();

  /**
   * Returns {@code user:password} login for TMCH MarksDB HTTP server SMDRL interface.
   *
   * @see google.registry.tmch.TmchSmdrlAction
   */
  String getMarksdbSmdrlLoginAndPassword();

  /**
   * Returns the credentials for a service account on the Google AppEngine project downloaded from
   * the Cloud Console dashboard in JSON format.
   */
  String getJsonCredential();

  // Don't throw so try-with-resources works better.
  @Override
  void close();
}
