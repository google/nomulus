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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.annotation.Serialize;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.util.CidrAddressBlock;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Set;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Base class of all unit tests for entities which are persisted to Datastore via Objectify. */
@RunWith(JUnit4.class)
public abstract class EntityTestCase {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withClock(fakeClock).build();

  @Rule public InjectRule inject = new InjectRule();

  @Before
  public void injectClock() {
    inject.setStaticField(Ofy.class, "clock", fakeClock);
  }

  // Helper method to find private fields including inherited ones.
  private Field getField(Class<?> clazz, String name) {
    while (clazz != Object.class) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    return null;
  }

  /** Verify that fields are either indexed or not, depending on the parameter. */
  private void verifyIndexingHelper(Object obj, boolean indexed, Collection<String> fieldPaths)
      throws Exception {
    outer:
    for (String fieldPath : fieldPaths) {
      // Walk the field path and grab the value referred to on the object using reflection.
      Object fieldValue = obj;
      for (String fieldName : Splitter.on('.').split(fieldPath)) {
        if (fieldValue == null) {
          throw new RuntimeException(
              String.format(
                  "field '%s' not found on %s", fieldPath, obj.getClass().getSimpleName()));
        }
        Field field = getField(fieldValue.getClass(), fieldName);
        field.setAccessible(true);
        // Check if the field is annotated with @Id. If it is, it's always indexed. Although we
        // might want to double check that fact, it's a bad idea, because filtering on a @Id field
        // will fail if the class also contains an @Parent field.
        if (field.isAnnotationPresent(Id.class)) {
          continue outer;
        }
        fieldValue = field.get(fieldValue);
        // Check if the field is a collection. If so, take the first value from the collection,
        // since we can't query on collections themselves.
        if (fieldValue instanceof Collection<?>) {
          fieldValue = ((Collection<?>) fieldValue).iterator().next();
        }
        // Same for an array.
        if (fieldValue != null && fieldValue.getClass().isArray()) {
          fieldValue = Array.getLength(fieldValue) == 0 ? null : Array.get(fieldValue, 0);
        }
        // CidrAddressBlock objects implement the Iterable<InetAddress> interface, which makes
        // Objectify think we're querying for a list (which is not allowed unless we use the IN
        // keyword). Convert the object to a String to make sure we do a query for a single value.
        if (fieldValue instanceof CidrAddressBlock) {
          fieldValue = fieldValue.toString();
        }
      }
      try {
        // Objectify happily filters on an unindexed field, and just returns zero results.
        // Do a query for that value and verify that the expected number of results are returned.
        Query<?> query = ofy().load().type(obj.getClass());
        int results = query.filter(fieldPath, fieldValue).count();
        assertWithMessage(String.format("%s was %sindexed", fieldPath, indexed ? "not " : ""))
            .that(indexed)
            .isEqualTo(results != 0);
      } catch (IllegalArgumentException e) {
        // If the field's type was not indexable (because it's not a supported Datastore type) then
        // this error will be thrown. If we expected no indexing, that's fine. Otherwise, fail.
        if (indexed || !e.getMessage().endsWith(" is not a supported property type.")) {
          assertWithMessage("%s was %sindexed", fieldPath, indexed ? "not " : "").fail();
        }
      } catch (IllegalStateException e) {
        assertWithMessage("%s (indexed=%b): %s", fieldPath, indexed, e.getMessage()).fail();
      }
    }
  }

  /** List all field paths so we can verify that we checked everything. */
  private Set<String> getAllPotentiallyIndexedFieldPaths(Class<?> clazz) {
    Set<String> fields = Sets.newHashSet();
    for (; clazz != Object.class; clazz = clazz.getSuperclass()) {
      for (final Field field : clazz.getDeclaredFields()) {
        // Ignore static, @Serialize, @Parent and @Ignore fields, since these are never indexed.
        if (!Modifier.isStatic(field.getModifiers())
            && !field.isAnnotationPresent(Ignore.class)
            && !field.isAnnotationPresent(Parent.class)
            && !field.isAnnotationPresent(Serialize.class)) {
          Class<?> fieldClass = field.getType();
          // If the field is a collection, just pretend that it was a field of that type,
          // because verifyIndexingHelper knows how to descend into collections.
          if (Collection.class.isAssignableFrom(fieldClass)) {
            Type inner = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            fieldClass =
                inner instanceof ParameterizedType
                    ? (Class<?>) ((ParameterizedType) inner).getRawType()
                    : (Class<?>) inner;
          }
          // Descend into persisted ImmutableObject classes, but not anything else.
          if (ImmutableObject.class.isAssignableFrom(fieldClass)) {
            getAllPotentiallyIndexedFieldPaths(fieldClass).stream()
                .map(subfield -> field.getName() + "." + subfield)
                .distinct()
                .forEachOrdered(fields::add);
          } else {
            fields.add(field.getName());
          }
        }
      }
    }
    return fields;
  }

  /** Verify indexing for an entity. */
  public void verifyIndexing(Object obj, String... indexed) throws Exception {
    Set<String> indexedSet = ImmutableSet.copyOf(indexed);
    Set<String> allSet = getAllPotentiallyIndexedFieldPaths(obj.getClass());
    // Sanity test that the indexed fields we listed were found.
    assertThat(Sets.intersection(allSet, indexedSet)).containsExactlyElementsIn(indexedSet);
    verifyIndexingHelper(obj, true, indexedSet);
    verifyIndexingHelper(obj, false, Sets.difference(allSet, indexedSet));
  }
}
