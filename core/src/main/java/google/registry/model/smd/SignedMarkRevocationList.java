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

package google.registry.model.smd;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.Map;
import java.util.Optional;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import org.joda.time.DateTime;

/**
 * Signed Mark Data Revocation List (SMDRL).
 *
 * <p>Represents a SMDRL file downloaded from the TMCH MarksDB each day. The list holds the ids of
 * all the {@link SignedMark SignedMarks} that have been revoked. A new list is created for each new
 * file that's created, depending on the timestamp.
 *
 * @see google.registry.tmch.SmdrlCsvParser
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.2">TMCH
 *     functional specifications - SMD Revocation List</a>
 */
@Entity
public class SignedMarkRevocationList extends ImmutableObject implements SqlEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long revisionId;

  /** Time when this list was last updated, as specified in the first line of the CSV file. */
  DateTime creationTime;

  /** A map from SMD IDs to revocation time. */
  @ElementCollection
  @CollectionTable(
      name = "SignedMarkRevocationEntry",
      joinColumns = @JoinColumn(name = "revisionId", referencedColumnName = "revisionId"))
  @MapKeyColumn(name = "smdId")
  @Column(name = "revocationTime", nullable = false)
  Map</*@MatchesPattern("[0-9]+-[0-9]+")*/ String, DateTime> revokes;

  /** A cached supplier that fetches the {@link SignedMarkRevocationList} object. */
  private static final Supplier<SignedMarkRevocationList> CACHE =
      memoizeWithShortExpiration(SignedMarkRevocationListDao::load);

  public static SignedMarkRevocationList get() {
    return CACHE.get();
  }

  /** Create a new {@link SignedMarkRevocationList} without saving it. */
  public static SignedMarkRevocationList create(
      DateTime creationTime, ImmutableMap<String, DateTime> revokes) {
    SignedMarkRevocationList instance = new SignedMarkRevocationList();
    instance.creationTime = checkNotNull(creationTime, "creationTime");
    instance.revokes = checkNotNull(revokes, "revokes");
    return instance;
  }

  /** Returns {@code true} if the SMD ID has been revoked at the given point in time. */
  public boolean isSmdRevoked(String smdId, DateTime now) {
    DateTime revoked = revokes.get(checkNotNull(smdId, "smdId"));
    return revoked != null && isBeforeOrAt(revoked, now);
  }

  /** Returns the creation timestamp specified at the top of the SMDRL CSV file. */
  public DateTime getCreationTime() {
    return creationTime;
  }

  /** Returns the number of revocations. */
  public int size() {
    return revokes.size();
  }

  /** Save this list to Cloud SQL. Returns {@code this}. */
  public SignedMarkRevocationList save() {
    SignedMarkRevocationListDao.save(this);
    return this;
  }

  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.empty(); // Not persisted in Datastore
  }
}
