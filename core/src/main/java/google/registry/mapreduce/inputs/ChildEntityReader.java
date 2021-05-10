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

import static google.registry.model.EntityClasses.ALL_CLASSES;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.appengine.tools.mapreduce.ShardContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import java.io.IOException;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Reader that maps over {@link EppResourceIndex} and returns resources that are children of
 * {@link EppResource} objects.
 */
class ChildEntityReader<R extends EppResource, I extends ImmutableObject> extends InputReader<I> {

  private static final long serialVersionUID = 7481761146349663848L;

  /** This reader uses an EppResourceEntityReader under the covers to iterate over EPP resources. */
  private final EppResourceEntityReader<? extends R> eppResourceEntityReader;

  /** The child resource classes to postfilter for. */
  private final ImmutableList<Class<? extends I>> childResourceClasses;

  /** The index within the list above for the next ofy query. */
  private int childResourceClassIndex;

  /** A reader used to go over children of the current eppResourceEntity and childResourceClass. */
  @Nullable private ChildReader<? extends I> childReader;

  public ChildEntityReader(
      Key<EppResourceIndexBucket> bucketKey,
      ImmutableSet<Class<? extends R>> resourceClasses,
      ImmutableSet<Class<? extends I>> childResourceClasses) {
    this.childResourceClasses = expandPolymorphicClasses(childResourceClasses);
    this.eppResourceEntityReader = new EppResourceEntityReader<>(bucketKey, resourceClasses);
  }

  /** Expands non-entity polymorphic classes into their child types. */
  @SuppressWarnings("unchecked")
  private ImmutableList<Class<? extends I>> expandPolymorphicClasses(
      ImmutableSet<Class<? extends I>> resourceClasses) {
    ImmutableList.Builder<Class<? extends I>> builder = new ImmutableList.Builder<>();
    for (Class<? extends I> clazz : resourceClasses) {
      if (clazz.isAnnotationPresent(Entity.class)) {
        builder.add(clazz);
      } else {
        for (Class<? extends ImmutableObject> entityClass : ALL_CLASSES) {
          if (clazz.isAssignableFrom(entityClass)) {
            builder.add((Class<? extends I>) entityClass);
          }
        }
      }
    }
    return builder.build();
  }

  /**
   * Get the next {@link ImmutableObject} (i.e. child element) from the query.
   *
   * @throws NoSuchElementException if there are no more EPP resources to iterate over.
   */
  I nextChild() throws NoSuchElementException {
    // This code implements a single iteration over a triple-nested loop. It returns the next
    // innermost item of that 3-nested loop. The entire loop would look like this:
    //
    // NOTE: I'm treating eppResourceEntityReader and childReader as if they were iterables for
    // brevity, although they aren't - they are Readers
    //
    // I'm also using the python 'yield' command to show we're returning this item one by one.
    //
    // for (eppResourceEntity : eppResourceEntityReader) {
    //   for (childResourceClass :  childResourceClasses) {
    //     for (I child : ChildReader.create(childResourceClass, Key.create(eppResourceEntity)) {
    //       yield child; // returns the 'child's one by one.
    //     }
    //   }
    // }

    // First, set all the variables if they aren't set yet. This should only happen on the first
    // time in the function.
    //
    // This can be merged with the calls in the "catch" below to avoid code duplication, but it
    // makes the code harder to read.
    if (childReader == null) {
      childResourceClassIndex = 0;
      childReader =
          ChildReader.create(
              childResourceClasses.get(childResourceClassIndex),
              Key.create(eppResourceEntityReader.next()));
    }
    // Then continue advancing the 3-nested loop until we find a value
    while (true) {
      try {
        // Advance the inner loop and return the next value.
        return childReader.next();
      } catch (NoSuchElementException e) {
        // If we got here it means the inner loop (childQueryIterator) is done - we need to advance
        // the middle loop by one, and then reset the inner loop.
        childResourceClassIndex++;
        // Check if the middle loop is done as well
        if (childResourceClassIndex < childResourceClasses.size()) {
          // The middle loop is not done. Reset the inner loop.
          childReader = childReader.withType(childResourceClasses.get(childResourceClassIndex));
        } else {
          // We're done with the middle loop as well! Advance the outer loop, and reset the middle
          // loop and inner loops
          childResourceClassIndex = 0;
          childReader =
              ChildReader.create(
                  childResourceClasses.get(childResourceClassIndex),
                  Key.create(eppResourceEntityReader.next()));
        }
        // Loop back up the while, to try reading reading a value again
      }
    }
  }

  @Override
  public I next() throws NoSuchElementException {
    while (true) {
      I entity = nextChild();
      if (entity != null) {
        // Postfilter to distinguish polymorphic types.
        for (Class<? extends I> resourceClass : childResourceClasses) {
          if (resourceClass.isInstance(entity)) {
            return entity;
          }
        }
      }
    }
  }

  @Override
  public void beginSlice() {
    eppResourceEntityReader.beginSlice();
    if (childReader != null) {
      childReader.beginSlice();
    }
  }

  @Override
  public void endSlice() {
    eppResourceEntityReader.endSlice();
    if (childReader != null) {
      childReader.endSlice();
    }
  }

  @Override
  public Double getProgress() {
    return eppResourceEntityReader.getProgress();
  }

  @Override
  public long estimateMemoryRequirement() {
    return eppResourceEntityReader.estimateMemoryRequirement();
  }

  @Override
  public ShardContext getContext() {
    return eppResourceEntityReader.getContext();
  }

  @Override
  public void setContext(ShardContext context) {
    eppResourceEntityReader.setContext(context);
  }

  @Override
  public void beginShard() {
    eppResourceEntityReader.beginShard();
  }

  @Override
  public void endShard() throws IOException {
    eppResourceEntityReader.endShard();
  }

  private static class ChildReader<I> extends RetryingInputReader<I, I> {

    private static final long serialVersionUID = -8443132445119657998L;

    private final Class<I> type;

    private final Key<?> ancestor;

    /** Create a reader that goes over all the children of a given type to the given ancestor. */
    public ChildReader(Class<I> type, Key<?> ancestor) {
      this.type = type;
      this.ancestor = ancestor;
      // This reader isn't initialized by mapreduce, so we need to initialize it ourselves
      beginShard();
      beginSlice();
    }

    /**
     * Create a reader that goes over all the children of a given type to the given ancestor.
     *
     * <p>We need this function in addition to the constructor so that we can create a ChildReader<?
     * extends I>.
     */
    public static <I> ChildReader<I> create(Class<I> type, Key<?> ancestor) {
      return new ChildReader<I>(type, ancestor);
    }

    /** Query for children of the current resource and of the current child class. */
    @Override
    public QueryResultIterator<I> getQueryIterator(Cursor cursor) {
      return startQueryAt(auditedOfy().load().type(type).ancestor(ancestor), cursor).iterator();
    }

    @Override
    public int getTotal() {
      return 0;
    }

    @Override
    public I next() {
      return nextQueryResult();
    }

    /** Returns a new ChildReader of the same ancestor for the given type. */
    public <J> ChildReader<J> withType(Class<J> type) {
      return create(type, ancestor);
    }
  }
}
