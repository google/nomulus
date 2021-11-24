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

package google.registry.model.index;

import static google.registry.util.TypeUtils.instantiate;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.BackupGroupRoot;
import google.registry.model.EppResource;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.replay.DatastoreOnlyEntity;

/** An index that allows for quick enumeration of all EppResource entities (e.g. via map reduce). */
@ReportedOn
@Entity
public class EppResourceIndex extends BackupGroupRoot implements DatastoreOnlyEntity {

  @Id String id;

  @Parent Key<EppResourceIndexBucket> bucket;

  /** Although this field holds a {@link Key} it is named "reference" for historical reasons. */
  Key<? extends EppResource> reference;

  @Index String kind;

  public String getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  public Key<? extends EppResource> getKey() {
    return reference;
  }

  @VisibleForTesting
  public Key<EppResourceIndexBucket> getBucket() {
    return bucket;
  }

  @VisibleForTesting
  public static <T extends EppResource> EppResourceIndex create(
      Key<EppResourceIndexBucket> bucket, Key<T> resourceKey) {
    EppResourceIndex instance = instantiate(EppResourceIndex.class);
    instance.reference = resourceKey;
    instance.kind = resourceKey.getKind();
    // TODO(b/207368050): figure out if this value has ever been used other than test cases
    instance.id = resourceKey.getString(); // creates a web-safe key string
    instance.bucket = bucket;
    return instance;
  }

  public static <T extends EppResource> EppResourceIndex create(Key<T> resourceKey) {
    return create(EppResourceIndexBucket.getBucketKey(resourceKey), resourceKey);
  }
}
