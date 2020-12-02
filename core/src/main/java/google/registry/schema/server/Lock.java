// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.server;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.ImmutableObject;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import google.registry.schema.server.Lock.LockId;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * A lock on some shared resource.
 *
 * <p>Locks are either specific to a tld or global to the entire system, in which case a tld of
 * {@link GLOBAL} is used.
 *
 * <p>This uses a compound primary key as defined in {@link LockId}.
 */
@Entity
@Table
@IdClass(LockId.class)
public class Lock implements SqlEntity {

  /** The resource name used to create the lock. */
  @Column(nullable = false)
  @Id
  String resourceName;

  /** The tld used to create the lock. */
  @Column(nullable = false)
  @Id
  String tld;

  /**
   * Unique log ID of the request that owns this lock.
   *
   * <p>When that request is no longer running (is finished), the lock can be considered implicitly
   * released.
   *
   * <p>See {@link RequestStatusCheckerImpl#getLogId} for details about how it's created in
   * practice.
   */
  @Column(nullable = false)
  String requestLogId;

  /** When the lock was acquired. Used for logging. */
  @Column(nullable = false)
  ZonedDateTime acquiredTime;

  /** When the lock can be considered implicitly released. */
  @Column(nullable = false)
  ZonedDateTime expirationTime;

  /** The scope of a lock that is not specific to a single tld. */
  static final String GLOBAL = "GLOBAL";

  /**
   * Validate input and create a new {@link Lock} for the given resource name in the specified tld.
   */
  private Lock(
      String resourceName,
      String tld,
      String requestLogId,
      DateTime acquiredTime,
      Duration leaseLength) {
    this.resourceName = checkArgumentNotNull(resourceName, "The resource name cannot be null");
    this.tld = checkArgumentNotNull(tld, "The tld cannot be null. For a global lock, use GLOBAL");
    this.requestLogId =
        checkArgumentNotNull(requestLogId, "The requestLogId of the lock cannot be null");
    this.acquiredTime =
        DateTimeUtils.toZonedDateTime(
            checkArgumentNotNull(acquiredTime, "The acquired time of the lock cannot be null"));
    checkArgumentNotNull(leaseLength, "The lease length of the lock cannot be null");
    this.expirationTime = DateTimeUtils.toZonedDateTime(acquiredTime.plus(leaseLength));
  }

  // Hibernate requires a default constructor.
  private Lock() {}

  /** Constructs a {@link Lock} object. */
  public static Lock create(
      String resourceName,
      String tld,
      String requestLogId,
      DateTime acquiredTime,
      Duration leaseLength) {
    checkArgumentNotNull(
        tld, "The tld cannot be null. To create a global lock, use the createGlobal method");
    return new Lock(resourceName, tld, requestLogId, acquiredTime, leaseLength);
  }

  /** Constructs a {@link Lock} object with a {@link GLOBAL} scope. */
  public static Lock createGlobal(
      String resourceName, String requestLogId, DateTime acquiredTime, Duration leaseLength) {
    return new Lock(resourceName, GLOBAL, requestLogId, acquiredTime, leaseLength);
  }

  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.empty(); // Locks are not converted since they are ephemeral
  }

  static class LockId extends ImmutableObject implements Serializable {

    String resourceName;

    String tld;

    private LockId() {}

    LockId(String resourceName, String tld) {
      this.resourceName = checkArgumentNotNull(resourceName, "The resource name cannot be null");
      this.tld = tld;
    }
  }
}
