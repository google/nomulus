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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.concurrent.Immutable;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;

/** A {@link Keyring} that uses in-memory values for all credentials. */
@Immutable
public final class InMemoryKeyring implements Keyring {

  private final PGPKeyPair rdeStagingKey;
  private final PGPKeyPair rdeSigningKey;
  private final PGPPublicKey rdeReceiverKey;
  private final PGPKeyPair brdaSigningKey;
  private final PGPPublicKey brdaEncryptionKey;
  private final String rdeSshClientPublicKey;
  private final String rdeSshClientPrivateKey;
  private final String icannReportingPassword;
  private final String safeBrowsingAPIKey;
  private final String marksdbDnlLoginAndPassword;
  private final String marksdbLordnPassword;
  private final String marksdbSmdrlLoginAndPassword;
  private final String jsonCredential;

  public InMemoryKeyring(
      PGPKeyPair rdeStagingKey,
      PGPKeyPair rdeSigningKey,
      PGPPublicKey rdeReceiverKey,
      PGPKeyPair brdaSigningKey,
      PGPPublicKey brdaEncryptionKey,
      String rdeSshClientPublicKey,
      String rdeSshClientPrivateKey,
      String icannReportingPassword,
      String safeBrowsingAPIKey,
      String marksdbDnlLoginAndPassword,
      String marksdbLordnPassword,
      String marksdbSmdrlLoginAndPassword,
      String jsonCredential,
      String cloudSqlPassword,
      String toolsCloudSqlPassword) {
    checkArgument(PgpHelper.isSigningKey(rdeSigningKey.getPublicKey()),
        "RDE signing key must support signing: %s", rdeSigningKey.getKeyID());
    checkArgument(rdeStagingKey.getPublicKey().isEncryptionKey(),
        "staging key must support encryption: %s", rdeStagingKey.getKeyID());
    checkArgument(rdeReceiverKey.isEncryptionKey(),
        "receiver key must support encryption: %s", rdeReceiverKey.getKeyID());
    checkArgument(PgpHelper.isSigningKey(brdaSigningKey.getPublicKey()),
        "BRDA signing key must support signing: %s", brdaSigningKey.getKeyID());
    checkArgument(brdaEncryptionKey.isEncryptionKey(),
        "encryption key must support encryption: %s", brdaEncryptionKey.getKeyID());
    this.rdeStagingKey = rdeStagingKey;
    this.rdeSigningKey = rdeSigningKey;
    this.rdeReceiverKey = rdeReceiverKey;
    this.brdaSigningKey = brdaSigningKey;
    this.brdaEncryptionKey = brdaEncryptionKey;
    this.rdeSshClientPublicKey = checkNotNull(rdeSshClientPublicKey, "rdeSshClientPublicKey");
    this.rdeSshClientPrivateKey = checkNotNull(rdeSshClientPrivateKey, "rdeSshClientPrivateKey");
    this.icannReportingPassword = checkNotNull(icannReportingPassword, "icannReportingPassword");
    this.safeBrowsingAPIKey = checkNotNull(safeBrowsingAPIKey, "safeBrowsingAPIKey");
    this.marksdbDnlLoginAndPassword =
        checkNotNull(marksdbDnlLoginAndPassword, "marksdbDnlLoginAndPassword");
    this.marksdbLordnPassword = checkNotNull(marksdbLordnPassword, "marksdbLordnPassword");
    this.marksdbSmdrlLoginAndPassword =
        checkNotNull(marksdbSmdrlLoginAndPassword, "marksdbSmdrlLoginAndPassword");
    this.jsonCredential = checkNotNull(jsonCredential, "jsonCredential");
  }

  @Override
  public PGPKeyPair getRdeSigningKey() {
    return rdeSigningKey;
  }

  @Override
  public PGPPublicKey getRdeStagingEncryptionKey() {
    return rdeStagingKey.getPublicKey();
  }

  @Override
  public PGPPrivateKey getRdeStagingDecryptionKey() {
    return rdeStagingKey.getPrivateKey();
  }

  @Override
  public PGPPublicKey getRdeReceiverKey() {
    return rdeReceiverKey;
  }

  @Override
  public PGPKeyPair getBrdaSigningKey() {
    return brdaSigningKey;
  }

  @Override
  public PGPPublicKey getBrdaReceiverKey() {
    return brdaEncryptionKey;
  }

  @Override
  public String getRdeSshClientPublicKey() {
    return rdeSshClientPublicKey;
  }

  @Override
  public String getRdeSshClientPrivateKey() {
    return rdeSshClientPrivateKey;
  }

  @Override
  public String getIcannReportingPassword() {
    return icannReportingPassword;
  }

  @Override
  public String getSafeBrowsingAPIKey() {
    return safeBrowsingAPIKey;
  }

    @Override
  public String getMarksdbDnlLoginAndPassword() {
    return marksdbDnlLoginAndPassword;
  }

  @Override
  public String getMarksdbLordnPassword() {
    return marksdbLordnPassword;
  }

  @Override
  public String getMarksdbSmdrlLoginAndPassword() {
    return marksdbSmdrlLoginAndPassword;
  }

  @Override
  public String getJsonCredential() {
    return jsonCredential;
  }

  /** Does nothing. */
  @Override
  public void close() {}
}
