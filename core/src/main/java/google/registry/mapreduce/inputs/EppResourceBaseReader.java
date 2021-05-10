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

package google.registry.mapreduce.inputs;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.EppResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import javax.annotation.Nullable;

/** Base class for {@link InputReader} classes that map over {@link EppResourceIndex}. */
abstract class EppResourceBaseReader<T> extends RetryingInputReader<EppResourceIndex, T> {

  /** Number of bytes in 1MB of memory, used for memory estimates. */
  static final long ONE_MB = 1024 * 1024;

  private static final long serialVersionUID = 7942584269402339168L;

  /**
   * The resource kinds to filter for.
   *
   * <p>This can be empty, or any of {"ContactResource", "HostResource", "DomainBase"}. It will
   * never contain "EppResource" since this isn't an actual kind in Datastore.
   */
  private final ImmutableSet<String> filterKinds;

  private final Key<EppResourceIndexBucket> bucketKey;
  private final long memoryEstimate;

  EppResourceBaseReader(
      Key<EppResourceIndexBucket> bucketKey,
      long memoryEstimate,
      ImmutableSet<String> filterKinds) {
    this.bucketKey = bucketKey;
    this.memoryEstimate = memoryEstimate;
    this.filterKinds = filterKinds;
  }

  @Override
  public QueryResultIterator<EppResourceIndex> getQueryIterator(@Nullable Cursor cursor) {
    return startQueryAt(query(), cursor).iterator();
  }

  @Override
  public int getTotal() {
    return query().count();
  }

  /** Query for children of this bucket. */
  Query<EppResourceIndex> query() {
    Query<EppResourceIndex> query =
        auditedOfy().load().type(EppResourceIndex.class).ancestor(bucketKey);
    return filterKinds.isEmpty() ? query : query.filter("kind in", filterKinds);
  }

  /** Returns the estimated memory that will be used by this reader in bytes. */
  @Override
  public long estimateMemoryRequirement() {
    return memoryEstimate;
  }

  static <R extends EppResource> ImmutableSet<String> varargsToKinds(
      ImmutableSet<Class<? extends R>> resourceClasses) {
    // Ignore EppResource when finding kinds, since it doesn't have one and doesn't imply filtering.
    return resourceClasses.contains(EppResource.class)
        ? ImmutableSet.of()
        : resourceClasses.stream().map(Key::getKind).collect(toImmutableSet());
  }
}
