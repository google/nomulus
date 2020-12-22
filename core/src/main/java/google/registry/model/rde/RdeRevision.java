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
import google.registry.schema.replay.NonReplicatedEntity;
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
public final class RdeRevision extends BackupGroupRoot implements NonReplicatedEntity {

  /** String triplet of tld, date, and mode, e.g. {@code soy_2015-09-01_full}. */
  @Id @Transient String id;

  @javax.persistence.Id @Ignore String tld;

  @javax.persistence.Id @Ignore LocalDate date;

  @javax.persistence.Id @Ignore RdeMode mode;

  /**
   * Number of last revision successfully staged to GCS.
   *
   * <p>This values begins at zero upon object creation and thenceforth incremented transactionally.
   */
  @Column(nullable = false)
  int revision;

  /** Hibernate requires an empty constructor. */
  private RdeRevision() {}

  public static RdeRevision create(
      String id, String tld, LocalDate date, RdeMode mode, int revision) {
    RdeRevision instance = new RdeRevision();
    instance.id = id;
    instance.tld = tld;
    instance.date = date;
    instance.mode = mode;
    instance.revision = revision;
    return instance;
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
    RdeRevisionId sqlKey = RdeRevisionId.create(tld, date.toLocalDate(), mode);
    Key<RdeRevision> ofyKey = Key.create(RdeRevision.class, id);
    Optional<RdeRevision> revisionOptional =
        tm().loadByKeyIfPresent(VKey.create(RdeRevision.class, sqlKey, ofyKey));
    return revisionOptional.map(rdeRevision -> rdeRevision.revision + 1).orElse(0);
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
    RdeRevisionId sqlKey = RdeRevisionId.create(tld, date.toLocalDate(), mode);
    Key<RdeRevision> ofyKey = Key.create(RdeRevision.class, triplet);
    Optional<RdeRevision> revisionOptional =
        tm().loadByKeyIfPresent(VKey.create(RdeRevision.class, sqlKey, ofyKey));
    if (revision == 0) {
      revisionOptional.ifPresent(
          rdeRevision -> {
            throw new IllegalArgumentException(
                String.format(
                    "RdeRevision object already created and revision 0 specified: %s",
                    rdeRevision));
          });
    } else {
      checkArgument(
          revisionOptional.isPresent(),
          "Couldn't find existing RDE revision %s when trying to save new revision %s",
          triplet,
          revision);
      checkArgument(
          revisionOptional.get().revision == revision - 1,
          "RDE revision object should be at revision %s but was: %s",
          revision - 1,
          revisionOptional.get());
    }
    RdeRevision object = RdeRevision.create(triplet, tld, date.toLocalDate(), mode, revision);
    tm().put(object);
  }

  /** Class to represent the composite primary key of {@link RdeRevision} entity. */
  static class RdeRevisionId extends ImmutableObject implements Serializable {

    String tld;

    // Auto-conversion doesn't work for ID classes, we must specify @Column and @Convert
    @Column(columnDefinition = "date")
    @Convert(converter = LocalDateConverter.class)
    LocalDate date;

    @Enumerated(EnumType.STRING)
    RdeMode mode;

    /** Hibernate requires this default constructor. */
    private RdeRevisionId() {}

    static RdeRevisionId create(String tld, LocalDate date, RdeMode mode) {
      RdeRevisionId instance = new RdeRevisionId();
      instance.tld = tld;
      instance.date = date;
      instance.mode = mode;
      return instance;
    }
  }
}
