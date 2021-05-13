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

package google.registry.model.translators;

import static com.google.common.base.MoreObjects.firstNonNull;
import static google.registry.config.RegistryConfig.getCommitLogDatastoreRetention;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import google.registry.model.ofy.CommitLogManifest;
import org.joda.time.DateTime;

/**
 * Objectify translator for {@code ImmutableSortedMap<DateTime, Key<CommitLogManifest>>} fields.
 *
 * <p>This translator is responsible for doing three things:
 * <ol>
 * <li>Translating the data into two lists of {@code Date} and {@code Key} objects, in a manner
 *   similar to {@code @Mapify}.
 * <li>Inserting a key to the transaction's {@link CommitLogManifest} on save.
 * <li>Truncating the map to include only the last key per day for the last 30 days.
 * </ol>
 *
 * <p>This allows you to have a field on your model object that tracks historical revisions of
 * itself, which can be binary searched for point-in-time restoration.
 *
 * <p><b>Warning:</b> Fields of this type must not be {@code null}, or else new entries can't be
 * inserted. You must take care to initialize the field to empty.
 *
 * @see google.registry.model.EppResource
 */
public final class CommitLogRevisionsTranslatorFactory
    extends ImmutableSortedMapTranslatorFactory<DateTime, Key<CommitLogManifest>> {

  /**
   * Add a reference to the current commit log to the resource's revisions map.
   *
   * <p>This method also prunes the revisions map. It guarantees to keep enough data so that floor
   * will work going back N days. It does this by making sure one entry exists before that duration,
   * and pruning everything after it. The size of the map is guaranteed to never exceed N+2.
   *
   * <p>We store a maximum of one entry per day. It will be the last transaction that happened on
   * that day.
   *
   * @see google.registry.config.RegistryConfig#getCommitLogDatastoreRetention()
   */
  @Override
  ImmutableSortedMap<DateTime, Key<CommitLogManifest>> transformBeforeSave(
      ImmutableSortedMap<DateTime, Key<CommitLogManifest>> revisions) {
    DateTime now = tm().getTransactionTime();
    DateTime threshold = now.minus(getCommitLogDatastoreRetention());
    DateTime preThresholdTime = firstNonNull(revisions.floorKey(threshold), START_OF_TIME);
    return new ImmutableSortedMap.Builder<DateTime, Key<CommitLogManifest>>(Ordering.natural())
        .putAll(revisions.subMap(preThresholdTime, true, now.withTimeAtStartOfDay(), false))
        .put(now, auditedOfy().getCommitLogManifestKey())
        .build();
  }
}
