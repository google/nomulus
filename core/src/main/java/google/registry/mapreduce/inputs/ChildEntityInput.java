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
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.index.EppResourceIndexBucket;

/**
 * A MapReduce {@link Input} that loads all child objects of a given set of types, that are children
 * of given {@link EppResource} types.
 */
@DeleteAfterMigration
class ChildEntityInput<R extends EppResource, I extends ImmutableObject>
    extends EppResourceBaseInput<I> {

  private static final long serialVersionUID = -3888034213150865008L;

  private final ImmutableSet<Class<? extends R>> resourceClasses;
  private final ImmutableSet<Class<? extends I>> childResourceClasses;

  public ChildEntityInput(
      ImmutableSet<Class<? extends R>> resourceClasses,
      ImmutableSet<Class<? extends I>> childResourceClasses) {
    this.resourceClasses = resourceClasses;
    this.childResourceClasses = childResourceClasses;
    checkNoInheritanceRelationships(ImmutableSet.copyOf(resourceClasses));
    checkNoInheritanceRelationships(ImmutableSet.copyOf(childResourceClasses));
  }

  @Override
  protected InputReader<I> bucketToReader(Key<EppResourceIndexBucket> bucketKey) {
    return new ChildEntityReader<>(bucketKey, resourceClasses, childResourceClasses);
  }
}
