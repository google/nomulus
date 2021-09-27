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

package google.registry.model;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.StackSize;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnLoad;
import google.registry.model.translators.UpdateAutoTimestampTranslatorFactory;
import google.registry.util.DateTimeUtils;
import java.time.ZonedDateTime;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.PostLoad;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A timestamp that auto-updates on each save to Datastore/Cloud SQL.
 *
 * @see UpdateAutoTimestampTranslatorFactory
 */
@Embeddable
public class UpdateAutoTimestamp extends ImmutableObject {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // When set to true, database converters/translators should do the auto update.  When set to
  // false, auto update should be suspended (this exists to allow us to preserve the original value
  // during a replay).
  private static ThreadLocal<Boolean> autoUpdateEnabled = ThreadLocal.withInitial(() -> true);

  @Transient DateTime timestamp;

  @Ignore
  @Column(nullable = false)
  ZonedDateTime lastUpdateTime;

  // Unfortunately, we cannot use the @UpdateTimestamp annotation on "lastUpdateTime" in this class
  // because Hibernate does not allow it to be used on @Embeddable classes, see
  // https://hibernate.atlassian.net/browse/HHH-13235. This is a workaround.
  @PrePersist
  @PreUpdate
  void setTimestamp() {
    // On the off chance that this is called outside of a transaction, log it instead of failing
    // with an exception from attempting to call jpaTm().getTransactionTime(), and then fall back
    // to DateTime.now(UTC).
    if (!jpaTm().inTransaction()) {
      logger.atSevere().withStackTrace(StackSize.MEDIUM).log(
          "Failed to update automatic timestamp because this wasn't called in a JPA transaction%s.",
          ofyTm().inTransaction() ? " (but there is an open Ofy transaction)" : "");
      timestamp = DateTime.now(UTC);
      lastUpdateTime = DateTimeUtils.toZonedDateTime(timestamp);
    } else if (autoUpdateEnabled() || lastUpdateTime == null) {
      timestamp = jpaTm().getTransactionTime();
      lastUpdateTime = DateTimeUtils.toZonedDateTime(timestamp);
    }
  }

  @OnLoad
  void onLoad() {
    if (timestamp != null) {
      lastUpdateTime = DateTimeUtils.toZonedDateTime(timestamp);
    }
  }

  @PostLoad
  void postLoad() {
    if (lastUpdateTime != null) {
      timestamp = DateTimeUtils.toJodaDateTime(lastUpdateTime);
    }
  }

  /** Returns the timestamp, or {@code START_OF_TIME} if it's null. */
  public DateTime getTimestamp() {
    return Optional.ofNullable(timestamp).orElse(START_OF_TIME);
  }

  public static UpdateAutoTimestamp create(@Nullable DateTime timestamp) {
    UpdateAutoTimestamp instance = new UpdateAutoTimestamp();
    instance.timestamp = timestamp;
    instance.lastUpdateTime = timestamp == null ? null : DateTimeUtils.toZonedDateTime(timestamp);
    return instance;
  }

  // TODO(b/175610935): Remove the auto-update disabling code below after migration.

  /** Class to allow us to safely disable auto-update in a try-with-resources block. */
  public static class DisableAutoUpdateResource implements AutoCloseable {
    DisableAutoUpdateResource() {
      autoUpdateEnabled.set(false);
    }

    @Override
    public void close() {
      autoUpdateEnabled.set(true);
    }
  }

  /**
   * Resturns a resource that disables auto-updates on all {@link UpdateAutoTimestamp}s in the
   * current thread, suitable for use with in a try-with-resources block.
   */
  public static DisableAutoUpdateResource disableAutoUpdate() {
    return new DisableAutoUpdateResource();
  }

  public static boolean autoUpdateEnabled() {
    return autoUpdateEnabled.get();
  }
}
