// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.comparedb;

import static google.registry.beam.comparedb.ValidateSqlUtils.createSqlEntityTupleTag;
import static google.registry.beam.initsql.Transforms.createTagForKind;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.backup.VersionedEntity;
import google.registry.beam.initsql.Transforms;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.Cursor;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.replay.SqlEntity;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.joda.time.DateTime;

/** Utilities for loading Datastore snapshots. */
@DeleteAfterMigration
public final class DatastoreSnapshots {

  private DatastoreSnapshots() {}

  /**
   * Datastore kinds eligible for validation. This set must be consistent with {@link
   * SqlSnapshots#ALL_SQL_ENTITIES}.
   */
  @VisibleForTesting
  static final ImmutableSet<Class<?>> ALL_DATASTORE_KINDS =
      ImmutableSet.of(
          Registry.class,
          Cursor.class,
          Registrar.class,
          ContactResource.class,
          RegistrarContact.class,
          HostResource.class,
          HistoryEntry.class,
          AllocationToken.class,
          BillingEvent.Recurring.class,
          BillingEvent.OneTime.class,
          BillingEvent.Cancellation.class,
          PollMessage.class,
          DomainBase.class);

  /**
   * Returns the Datastore snapshot right before {@code commitLogToTime} for the user specified
   * {@code kinds}. The resulting snapshot has all changes that happened before {@code
   * commitLogToTime}, and none at or after {@code commitLogToTime}.
   *
   * <p>If {@code HistoryEntry} is included in {@code kinds}, the result will contain {@code
   * PCollections} for the child entities, {@code DomainHistory}, {@code ContactHistory}, and {@code
   * HostHistory}.
   */
  static PCollectionTuple loadDatastoreSnapshotByKind(
      Pipeline pipeline,
      String exportDir,
      String commitLogDir,
      DateTime commitLogFromTime,
      DateTime commitLogToTime,
      Set<Class<?>> kinds) {
    PCollectionTuple snapshot =
        pipeline.apply(
            "Load Datastore snapshot.",
            Transforms.loadDatastoreSnapshot(
                exportDir,
                commitLogDir,
                commitLogFromTime,
                commitLogToTime,
                kinds.stream().map(Key::getKind).collect(ImmutableSet.toImmutableSet())));

    PCollectionTuple perTypeSnapshots = PCollectionTuple.empty(pipeline);
    for (Class<?> kind : kinds) {
      PCollection<VersionedEntity> perKindSnapshot =
          snapshot.get(createTagForKind(Key.getKind(kind)));
      if (SqlEntity.class.isAssignableFrom(kind)) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag((Class<? extends SqlEntity>) kind),
                datastoreEntityToPojo(perKindSnapshot, kind.getSimpleName()));
        continue;
      }
      Verify.verify(kind == HistoryEntry.class, "Unexpected Non-SqlEntity class: %s", kind);
      PCollectionTuple historyEntriesByType = splitHistoryEntry(perKindSnapshot);
      for (Map.Entry<TupleTag<?>, PCollection<?>> entry :
          historyEntriesByType.getAll().entrySet()) {
        perTypeSnapshots = perTypeSnapshots.and(entry.getKey().getId(), entry.getValue());
      }
    }
    return perTypeSnapshots;
  }

  /**
   * Splits a {@link PCollection} of {@link HistoryEntry HistoryEntries} into three collections of
   * its child entities by type.
   */
  static PCollectionTuple splitHistoryEntry(PCollection<VersionedEntity> historyEntries) {
    return historyEntries.apply(
        "Split HistoryEntry by Resource Type",
        ParDo.of(
                new DoFn<VersionedEntity, SqlEntity>() {
                  @ProcessElement
                  public void processElement(
                      @Element VersionedEntity historyEntry, MultiOutputReceiver out) {
                    Optional.ofNullable(Transforms.convertVersionedEntityToSqlEntity(historyEntry))
                        .ifPresent(
                            sqlEntity ->
                                out.get(createSqlEntityTupleTag(sqlEntity.getClass()))
                                    .output(sqlEntity));
                  }
                })
            .withOutputTags(
                createSqlEntityTupleTag(DomainHistory.class),
                TupleTagList.of(createSqlEntityTupleTag(ContactHistory.class))
                    .and(createSqlEntityTupleTag(HostHistory.class))));
  }

  /**
   * Transforms a {@link PCollection} of {@link VersionedEntity VersionedEntities} to Ofy Java
   * objects.
   */
  static PCollection<SqlEntity> datastoreEntityToPojo(
      PCollection<VersionedEntity> entities, String desc) {
    return entities.apply(
        "Datastore Entity to Pojo " + desc,
        ParDo.of(
            new DoFn<VersionedEntity, SqlEntity>() {
              @ProcessElement
              public void processElement(
                  @Element VersionedEntity entity, OutputReceiver<SqlEntity> out) {
                Optional.ofNullable(Transforms.convertVersionedEntityToSqlEntity(entity))
                    .ifPresent(out::output);
              }
            }));
  }
}
