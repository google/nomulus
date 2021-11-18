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

import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnLoad;
import google.registry.model.translators.CreateAutoTimestampTranslatorFactory;
import google.registry.util.DateTimeUtils;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.PostLoad;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A timestamp that auto-updates when first saved to Datastore.
 *
 * @see CreateAutoTimestampTranslatorFactory
 */
@Embeddable
public class CreateAutoTimestamp extends ImmutableObject implements UnsafeSerializable {

  @Transient DateTime timestamp;

  @Column(nullable = false)
  @Ignore
  ZonedDateTime creationTime;

  @PrePersist
  @PreUpdate
  void setTimestamp() {
    if (creationTime == null) {
      timestamp = jpaTm().getTransactionTime();
      creationTime = DateTimeUtils.toZonedDateTime(timestamp);
    }
  }

  @OnLoad
  void onLoad() {
    if (timestamp != null) {
      creationTime = DateTimeUtils.toZonedDateTime(timestamp);
    }
  }

  @PostLoad
  void postLoad() {
    if (creationTime != null) {
      timestamp = DateTimeUtils.toJodaDateTime(creationTime);
    }
  }

  /** Returns the timestamp. */
  @Nullable
  public DateTime getTimestamp() {
    return timestamp;
  }

  public static CreateAutoTimestamp create(@Nullable DateTime timestamp) {
    CreateAutoTimestamp instance = new CreateAutoTimestamp();
    instance.timestamp = timestamp;
    instance.creationTime = (timestamp == null) ? null : DateTimeUtils.toZonedDateTime(timestamp);
    return instance;
  }
}
