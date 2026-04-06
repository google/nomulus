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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toInstant;

import google.registry.persistence.EntityCallbacksListener.RecursivePrePersist;
import google.registry.persistence.EntityCallbacksListener.RecursivePreUpdate;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** A timestamp that auto-updates on each save to Cloud SQL. */
@Embeddable
public class UpdateAutoTimestamp extends ImmutableObject implements UnsafeSerializable {

  @Column(name = "updateTimestamp")
  Instant lastUpdateTime;

  // Unfortunately, we cannot use the @UpdateTimestamp annotation on "lastUpdateTime" in this class
  // because Hibernate does not allow it to be used on @Embeddable classes, see
  // https://hibernate.atlassian.net/browse/HHH-13235. This is a workaround.
  @RecursivePrePersist
  @RecursivePreUpdate
  public void setTimestamp() {
    lastUpdateTime = tm().getTxTime();
  }

  /** Returns the timestamp, or {@code START_INSTANT} if it's null. */
  public Instant getTimestamp() {
    return Optional.ofNullable(lastUpdateTime).orElse(START_INSTANT);
  }

  /**
   * @deprecated Use {@link #getTimestamp()}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public DateTime getTimestampDateTime() {
    return toDateTime(getTimestamp());
  }

  public static UpdateAutoTimestamp create(@Nullable Instant timestamp) {
    UpdateAutoTimestamp instance = new UpdateAutoTimestamp();
    instance.lastUpdateTime = timestamp;
    return instance;
  }

  /**
   * @deprecated Use {@link #create(Instant)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static UpdateAutoTimestamp create(@Nullable DateTime timestamp) {
    return create(toInstant(timestamp));
  }
}
