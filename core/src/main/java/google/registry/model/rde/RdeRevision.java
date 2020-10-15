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

package google.registry.model.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.rde.RdeNamingUtils.makePartialName;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.VerifyException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.BackupGroupRoot;
import google.registry.model.ImmutableObject;
import google.registry.model.rde.RdeRevision.RdeRevisionId;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.LocalDateConverter;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import java.io.Serializable;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.IdClass;
import javax.persistence.Transient;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;

/**
 * Datastore entity for tracking RDE revisions.
 *
 * <p>This class is used by the RDE staging, upload, and reporting systems to determine the revision
 * that should be used in the generated filename. It also determines whether or not a {@code resend}
 * flag is included in the generated XML.
 */
@Entity
@javax.persistence.Entity
@IdClass(RdeRevisionId.class)
public final class RdeRevision extends BackupGroupRoot implements DatastoreAndSqlEntity {

  /** String triplet of tld, date, and mode, e.g. {@code soy_2015-09-01_full}. */
  @Id @Transient String id;

  @javax.persistence.Id
  @Ignore
  String tld;

  @javax.persistence.Id
  @Ignore
  LocalDate date;

  @javax.persistence.Id
  @Ignore
  RdeMode mode;

  /**
   * Number of last revision successfully staged to GCS.
   *
   * <p>This values begins at zero upon object creation and thenceforth incremented transactionally.
   */
  @Column(nullable = false)
  int revision;

  /** Hibernate requires an empty constructor. */
  private RdeRevision() {}

  public RdeRevision(String id, String tld, LocalDate date, RdeMode mode, int revision) {
    this.id = id;
    this.tld = tld;
    this.date = date;
    this.mode = mode;
    this.revision = revision;
  }

  public int getRevision() {
    return revision;
  }

  /**
   * Returns next revision ID to use when staging a new deposit file for the given triplet.
   *
   * @return {@code 0} for first deposit generation and {@code >0} for resends
   */
  public static int getNextRevision(String tld, DateTime date, RdeMode mode) {
    String id = makePartialName(tld, date, mode);
    RdeRevisionId sqlKey = new RdeRevisionId(tld, date.toLocalDate(), mode);
    Key<RdeRevision> ofyKey = Key.create(RdeRevision.class, id);
    Optional<RdeRevision> maybeObject =
        tm().maybeLoad(VKey.create(RdeRevision.class, sqlKey, ofyKey));
    return maybeObject.map(object -> object.revision + 1).orElse(0);
  }

  /**
   * Sets the revision ID for a given triplet.
   *
   * <p>This method verifies that the current revision is {@code revision - 1}, or that the object
   * does not exist in Datastore if {@code revision == 0}.
   *
   * @throws IllegalStateException if not in a transaction
   * @throws VerifyException if Datastore state doesn't meet the above criteria
   */
  public static void saveRevision(String tld, DateTime date, RdeMode mode, int revision) {
    checkArgument(revision >= 0, "Negative revision: %s", revision);
    String triplet = makePartialName(tld, date, mode);
    tm().assertInTransaction();
    RdeRevisionId sqlKey = new RdeRevisionId(tld, date.toLocalDate(), mode);
    Key<RdeRevision> ofyKey = Key.create(RdeRevision.class, triplet);
    Optional<RdeRevision> maybeObject =
        tm().maybeLoad(VKey.create(RdeRevision.class, sqlKey, ofyKey));
    if (revision == 0) {
      maybeObject.ifPresent(
          obj -> {
            throw new IllegalArgumentException(
                String.format("RdeRevision object already created: %s", obj));
          });
    } else {
      checkArgument(
          maybeObject.isPresent(),
          "RDE revision object missing for %s?! revision=%s",
          triplet,
          revision);
      checkArgument(
          maybeObject.get().revision == revision - 1,
          "RDE revision object should be at %s but was: %s",
          revision - 1,
          maybeObject.get());
    }
    RdeRevision object = new RdeRevision(triplet, tld, date.toLocalDate(), mode, revision);
    tm().put(object);
  }

  /** Class to represent the composite primary key of {@link RdeRevision} entity. */
  static class RdeRevisionId extends ImmutableObject implements Serializable {

    private String tld;

    // Auto-conversion doesn't work for ID classes, we must specify @Column and @Convert
    @Column
    @Convert(converter = LocalDateConverter.class)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    private RdeMode mode;

    /** Hibernate requires this default constructor. */
    private RdeRevisionId() {}

    RdeRevisionId(String tld, LocalDate date, RdeMode mode) {
      this.tld = tld;
      this.date = date;
      this.mode = mode;
    }

    /** Private, only used by Hibernate. */
    @SuppressWarnings("unused")
    private String getTld() {
      return tld;
    }

    /** Private, only used by Hibernate. */
    @SuppressWarnings("unused")
    private LocalDate getDate() {
      return date;
    }

    /** Private, only used by Hibernate. */
    @SuppressWarnings("unused")
    private RdeMode getMode() {
      return mode;
    }

    /** Required by Hibernate, should not be used in order to maintain immutability. */
    @SuppressWarnings("unused")
    private void setTld(String tld) {
      this.tld = tld;
    }

    /** Required by Hibernate, should not be used in order to maintain immutability. */
    @SuppressWarnings("unused")
    private void setDate(LocalDate date) {
      this.date = date;
    }

    /** Required by Hibernate, should not be used in order to maintain immutability. */
    @SuppressWarnings("unused")
    private void setMode(RdeMode mode) {
      this.mode = mode;
    }
  }
}
