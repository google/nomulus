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

package google.registry.model.tmch;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.common.CrossTldSingleton;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.Optional;
import javax.annotation.concurrent.Immutable;
import javax.persistence.Column;
import org.joda.time.DateTime;

/** Datastore singleton for ICANN's TMCH CA certificate revocation list (CRL). */
@Entity
@javax.persistence.Entity
@Immutable
@NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
public final class TmchCrl extends CrossTldSingleton implements DatastoreEntity, SqlEntity {

  @Column(nullable = false)
  String crl;

  @Column(nullable = false)
  DateTime updated;

  @Column(nullable = false)
  String url;

  /** Returns the singleton instance of this entity, without memoization. */
  public static Optional<TmchCrl> get() {
    VKey<TmchCrl> key =
        VKey.create(
            TmchCrl.class, SINGLETON_ID, Key.create(getCrossTldKey(), TmchCrl.class, SINGLETON_ID));
    return tm().transact(() -> tm().maybeLoad(key));
  }

  /**
   * Change the Datastore singleton to a new ASCII-armored X.509 CRL.
   *
   * <p>Please do not call this function unless your CRL is properly formatted, signed by the root,
   * and actually newer than the one currently in Datastore.
   *
   * <p>During the dual-write period, we write to both Datastore and SQL
   */
  public static void set(final String crl, final String url) {
    tm().transact(
            () -> {
              TmchCrl tmchCrl = new TmchCrl();
              tmchCrl.updated = tm().getTransactionTime();
              tmchCrl.crl = checkNotNull(crl, "crl");
              tmchCrl.url = checkNotNull(url, "url");
              ofyTm().transactNew(() -> ofyTm().putWithoutBackup(tmchCrl));
              jpaTm().transactNew(() -> jpaTm().putWithoutBackup(tmchCrl));
            });
  }

  /** ASCII-armored X.509 certificate revocation list. */
  public final String getCrl() {
    return crl;
  }

  /** Returns the URL that the CRL was downloaded from. */
  public final String getUrl() {
    return crl;
  }

  /** Time we last updated the Datastore with a newer ICANN CRL. */
  public final DateTime getUpdated() {
    return updated;
  }

  @Override
  public ImmutableList<SqlEntity> toSqlEntities() {
    return ImmutableList.of(); // dually-written
  }

  @Override
  public ImmutableList<DatastoreEntity> toDatastoreEntities() {
    return ImmutableList.of(); // dually-written
  }
}
