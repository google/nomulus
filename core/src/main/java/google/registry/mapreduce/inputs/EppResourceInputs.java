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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.asList;

import com.google.appengine.tools.mapreduce.Input;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.index.EppResourceIndex;

/**
 * Mapreduce helpers for {@link EppResource} keys and objects.
 *
 * <p>The inputs provided by this class are not deletion-aware and do not project the resources
 * forward in time. That is the responsibility of mappers that use these inputs.
 */
public final class EppResourceInputs {

  private EppResourceInputs() {}

  /** Returns a MapReduce {@link Input} that loads all {@link EppResourceIndex} objects. */
  public static <R extends EppResource> Input<EppResourceIndex> createIndexInput() {
    return new EppResourceIndexInput();
  }

  /**
   * Returns a MapReduce {@link Input} that loads all {@link EppResource} objects of a given type,
   * including deleted resources.
   *
   * <p>Note: Do not concatenate multiple EntityInputs together (this is inefficient as it iterates
   * through all buckets multiple times). Specify the types in a single input, or load all types by
   * specifying {@link EppResource} as the class.
   */
  @SafeVarargs
  public static <R extends EppResource> Input<R> createEntityInput(
      Class<? extends R> resourceClass,
      Class<? extends R>... moreResourceClasses) {
    return new EppResourceEntityInput<>(
        ImmutableSet.copyOf(asList(resourceClass, moreResourceClasses)));
  }

  /**
   * Returns a MapReduce {@link Input} that loads all {@link ImmutableObject} objects of a given
   * type, including deleted resources, that are child entities of all {@link EppResource} objects
   * of a given type.
   *
   * <p>Note: Do not concatenate multiple EntityInputs together (this is inefficient as it iterates
   * through all buckets multiple times). Specify the types in a single input, or load all types by
   * specifying {@link EppResource} and/or {@link ImmutableObject} as the class.
   */
  public static <R extends EppResource, I extends ImmutableObject> Input<I> createChildEntityInput(
      ImmutableSet<Class<? extends R>> parentClasses,
      ImmutableSet<Class<? extends I>> childClasses) {
    checkArgument(!parentClasses.isEmpty(), "Must provide at least one parent type.");
    checkArgument(!childClasses.isEmpty(), "Must provide at least one child type.");
    return new ChildEntityInput<>(parentClasses, childClasses);
  }

  /**
   * Returns a MapReduce {@link Input} that loads keys to all {@link EppResource} objects of a given
   * type, including deleted resources.
   *
   * <p>Note: Do not concatenate multiple KeyInputs together (this is inefficient as it iterates
   * through all buckets multiple times). Specify the types in a single input, or load all types by
   * specifying {@link EppResource} as the class.
   */
  @SafeVarargs
  public static <R extends EppResource> Input<Key<R>> createKeyInput(
      Class<? extends R> resourceClass, Class<? extends R>... moreResourceClasses) {
    return new EppResourceKeyInput<>(
        ImmutableSet.copyOf(asList(resourceClass, moreResourceClasses)));
  }
}
