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

import static google.registry.util.TypeUtils.checkNoInheritanceRelationships;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.index.EppResourceIndexBucket;

/**
 * A MapReduce {@link Input} that loads keys to all {@link EppResource} objects of a given type.
 *
 * <p>When mapping over keys we can't distinguish between Objectify polymorphic types.
 */
@DeleteAfterMigration
class EppResourceKeyInput<R extends EppResource> extends EppResourceBaseInput<Key<R>> {

  private static final long serialVersionUID = -5426821384707653743L;

  private final ImmutableSet<Class<? extends R>> resourceClasses;

  public EppResourceKeyInput(ImmutableSet<Class<? extends R>> resourceClasses) {
    this.resourceClasses = resourceClasses;
    checkNoInheritanceRelationships(ImmutableSet.copyOf(resourceClasses));
  }

  @Override
  protected InputReader<Key<R>> bucketToReader(Key<EppResourceIndexBucket> bucketKey) {
    return new EppResourceKeyReader<>(bucketKey, resourceClasses);
  }
}
