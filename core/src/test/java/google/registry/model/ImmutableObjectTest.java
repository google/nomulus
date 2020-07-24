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

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newHashSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObject.cloneEmptyToNull;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.testing.AppEngineExtension;
import google.registry.util.CidrAddressBlock;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ImmutableObject}. */
public class ImmutableObjectTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(ValueObject.class)
          .build();

  /** Simple subclass of ImmutableObject. */
  public static class SimpleObject extends ImmutableObject {
    String a;
    String b;

    SimpleObject(String a, String b) {
      this.a = a;
      this.b = b;
    }
  }

  @Test
  void testToString_simpleClass() {
    SimpleObject object = new SimpleObject("foo", null);
    assertThat(object.toString()).isEqualTo(""
        + "SimpleObject (@" + System.identityHashCode(object) + "): {\n"
        + "    a=foo\n"
        + "    b=null\n"
        + "}");
  }

  @Test
  void testToDiffableFieldMap_simpleClass() {
    SimpleObject object = new SimpleObject("foo", null);
    assertThat(object.toDiffableFieldMap()).containsEntry("a", "foo");
    assertThat(object.toDiffableFieldMap()).containsEntry("b", null);
  }

  /** Subclass of ImmutableObject with some more interesting non-collection types. */
  public static class TypesObject extends ImmutableObject {
    boolean bool;
    Boolean boolObject;
    int integer;
    Integer integerObject;
    DateTime datetime;
  }

  @Test
  void testToDiffableFieldMap_typesClass() {
    TypesObject object = new TypesObject();
    object.bool = true;
    object.boolObject = true;
    object.integer = 1;
    object.integerObject = 1;
    object.datetime = START_OF_TIME;
    assertThat(object.toDiffableFieldMap()).containsEntry("bool", true);
    assertThat(object.toDiffableFieldMap()).containsEntry("boolObject", true);
    assertThat(object.toDiffableFieldMap()).containsEntry("integer", 1);
    assertThat(object.toDiffableFieldMap()).containsEntry("integerObject", 1);
    assertThat(object.toDiffableFieldMap()).containsEntry("datetime", "1970-01-01T00:00:00.000Z");
  }

  /** Subclass of ImmutableObject with a nested ImmutableObject. */
  public static class NestedObject extends ImmutableObject {
    ImmutableObject nested;

    NestedObject(ImmutableObject nested) {
      this.nested = nested;
    }
  }

  @Test
  void testToDiffableFieldMap_nestedObjectClass() {
    SimpleObject innermostObject = new SimpleObject("foo", "bar");
    NestedObject innerObject = new NestedObject(innermostObject);
    NestedObject object = new NestedObject(innerObject);
    assertThat(innerObject.toDiffableFieldMap())
        .containsEntry("nested", innermostObject.toDiffableFieldMap());
    assertThat(object.toDiffableFieldMap())
        .containsEntry("nested", innerObject.toDiffableFieldMap());
  }

  /** Subclass of ImmutableObject with collections of nested ImmutableObjects. */
  public static class NestedCollectionsObject extends ImmutableObject {
    Set<SimpleObject> set;
    List<SimpleObject> list;
    Deque<SimpleObject> deque;
    Map<String, SimpleObject> map;
  }

  @Test
  void testToDiffableFieldMap_nestedObjectCollectionsClass() {
    SimpleObject obj1 = new SimpleObject("foo", "bar");
    SimpleObject obj2 = new SimpleObject("bax", "bar");
    Map<?, ?> obj1map = obj1.toDiffableFieldMap();
    Map<?, ?> obj2map = obj2.toDiffableFieldMap();
    NestedCollectionsObject object = new NestedCollectionsObject();
    object.set = ImmutableSet.of(obj1, obj2);
    object.list = ImmutableList.of(obj1, obj2);
    object.deque = new ArrayDeque<>(Arrays.asList(obj1,  obj2));
    object.map = ImmutableMap.of("one", obj1, "two", obj2);

    Map<?, ?> objectMap = object.toDiffableFieldMap();
    assertThat((Set<?>) objectMap.get("set")).containsExactly(obj1map, obj2map);
    assertThat((List<?>) objectMap.get("list")).containsExactly(obj1map, obj2map);
    assertThat((List<?>) objectMap.get("deque")).containsExactly(obj1map, obj2map);
    assertThat((Map<?, ?>) objectMap.get("map")).containsEntry("one", obj1map);
    assertThat((Map<?, ?>) objectMap.get("map")).containsEntry("two", obj2map);
  }

  /** Subclass of ImmutableObject with an iterable field. */
  public static class IterableObject extends ImmutableObject {
    Iterable<?> iterable;

    IterableObject(Iterable<?> iterable) {
      this.iterable = iterable;
    }
  }

  @Test
  void testToDiffableFieldMap_iterableField_notExpanded() {
    IterableObject iterableObject = new IterableObject(new CidrAddressBlock("127.0.0.1/32"));
    assertThat(iterableObject.toDiffableFieldMap()).containsEntry("iterable", "127.0.0.1/32");
  }

  @Test
  void testToDiffableFieldMap_infiniteIterableField_notExpanded() {
    IterableObject iterableObject = new IterableObject(Iterables.cycle("na"));
    assertThat(iterableObject.toDiffableFieldMap()).containsEntry("iterable", "[na] (cycled)");
  }

  /** Subclass of ImmutableObject with fields that should become empty after cloneEmptyToNull. */
  public static class EmptyableObject extends ImmutableObject {
    String nullString = null;
    String emptyString = "";
    String fullString = "a";
    SimpleObject nullSimpleObject = null;
    SimpleObject emptySimpleObject = new SimpleObject("", "");
    SimpleObject fullSimpleObject = new SimpleObject("a", "b");
    Object[] nullArray = null;
    Object[] emptyArray = new Object[]{};
    Object[] fullArray = new Object[]{"a"};
    List<?> nullList = null;
    List<?> emptyList = newArrayList();
    List<?> stringList = newArrayList("", null);
    List<?> immutableObjectList = newArrayList(new SimpleObject("", ""), null);
    List<?> heterogenousList = newArrayList(new SimpleObject("", ""), "");
    Set<?> nullSet = null;
    Set<?> emptySet = newHashSet();
    Set<?> stringSet = newHashSet("", null);
    Set<?> immutableObjectSet = newHashSet(new SimpleObject("", ""), null);
    Set<?> heterogenousSet = newHashSet(new SimpleObject("", ""), "");
    Map<?, ?> nullMap = null;
    Map<?, ?> emptyMap = newHashMap();
    Map<Object, Object> stringMap = newHashMap();
    Map<Object, Object> immutableObjectMap = newHashMap();
    Map<Object, Object> heterogenousMap = newHashMap();

    EmptyableObject() {
      stringMap.put("a", "");
      stringMap.put("b", null);
      immutableObjectMap.put("a", new SimpleObject("", ""));
      immutableObjectMap.put("b", null);
      heterogenousMap.put("a", new SimpleObject("", ""));
      heterogenousMap.put("b", "");
    }
  }

  @Test
  void testCloneEmptyToNull() {
    EmptyableObject cloned = cloneEmptyToNull(new EmptyableObject());
    assertThat(cloned.nullString).isNull();
    assertThat(cloned.emptyString).isNull();
    assertThat(cloned.fullString).isEqualTo("a");
    assertThat(cloned.nullSimpleObject).isNull();
    assertThat(cloned.emptySimpleObject.a).isNull();
    assertThat(cloned.emptySimpleObject.b).isNull();
    assertThat(cloned.fullSimpleObject.a).isEqualTo("a");
    assertThat(cloned.fullSimpleObject.b).isEqualTo("b");
    assertThat(cloned.nullList).isNull();
    assertThat(cloned.emptyList).isNull();
    assertThat(cloned.nullArray).isNull();
    assertThat(cloned.emptyArray).isNull();
    assertThat(cloned.fullArray).asList().containsExactly("a");
    assertThat(cloned.stringList).containsExactly("", null);
    assertThat(cloned.immutableObjectList).containsExactly(new SimpleObject(null, null), null);
    assertThat(cloned.heterogenousList).containsExactly(new SimpleObject("", ""), "");
    assertThat(cloned.nullSet).isNull();
    assertThat(cloned.emptySet).isNull();
    assertThat(cloned.stringSet).containsExactly("", null);
    assertThat(cloned.immutableObjectSet).containsExactly(new SimpleObject(null, null), null);
    assertThat(cloned.heterogenousSet).containsExactly(new SimpleObject("", ""), "");
    assertThat(cloned.nullMap).isNull();
    assertThat(cloned.emptyMap).isNull();
    assertThat(cloned.stringMap).containsEntry("a", "");
    assertThat(cloned.stringMap).containsEntry("b", null);
    assertThat(cloned.immutableObjectMap).containsEntry("a", new SimpleObject(null, null));
    assertThat(cloned.immutableObjectMap).containsEntry("b", null);
    assertThat(cloned.heterogenousMap).containsEntry("a", new SimpleObject("", ""));
    assertThat(cloned.heterogenousMap).containsEntry("b", "");
  }

  /** Subclass of ImmutableObject with fields that are containers containing null values. */
  public static class NullInContainersObject extends ImmutableObject {
    Object[] array = new Object[] {null};
    List<?> list = newArrayList((Object) null);
    Set<?> set = newHashSet((Object) null);
    Map<String, ?> map = newHashMap();

    NullInContainersObject() {
      map.put("a", null);
    }
  }

  @Test
  void testToDiffableFieldMap_withEmptyAndNulls() {
    Map<String, Object> diffableFieldMap = new NullInContainersObject().toDiffableFieldMap();
    assertThat((List<?>) diffableFieldMap.get("array")).containsExactly((Object) null);
    assertThat((List<?>) diffableFieldMap.get("list")).containsExactly((Object) null);
    assertThat((Set<?>) diffableFieldMap.get("set")).containsExactly((Object) null);
    assertThat((Map<?, ?>) diffableFieldMap.get("map")).containsExactly("a", null);
  }

  /** Subclass of ImmutableObject with keys to other objects. */
  public static class RootObject extends ImmutableObject {

    Key<ValueObject> hydrateMe;

    @DoNotHydrate
    Key<ValueObject> skipMe;

    Map<String, Key<ValueObject>> map;

    Set<Key<ValueObject>> set;
  }

  /** Simple subclass of ImmutableObject. */
  @Entity
  public static class ValueObject extends ImmutableObject {
    @Id
    long id;

    String value;

    static ValueObject create(long id, String value) {
      ValueObject instance = new ValueObject();
      instance.id = id;
      instance.value = value;
      return instance;
    }
  }

  @Test
  void testToHydratedString_skipsDoNotHydrate() {
    RootObject root = new RootObject();
    root.hydrateMe = Key.create(persistResource(ValueObject.create(1, "foo")));
    root.skipMe = Key.create(persistResource(ValueObject.create(2, "bar")));
    String hydratedString = root.toHydratedString();
    assertThat(hydratedString).contains("foo");
    assertThat(hydratedString).doesNotContain("bar");
  }

  @Test
  void testToHydratedString_expandsMaps() {
    RootObject root = new RootObject();
    root.map = ImmutableMap.of("foo", Key.create(persistResource(ValueObject.create(1, "bar"))));
    String hydratedString = root.toHydratedString();
    assertThat(hydratedString).contains("foo");
    assertThat(hydratedString).contains("bar");
  }

  @Test
  void testToHydratedString_expandsCollections() {
    RootObject root = new RootObject();
    root.set = ImmutableSet.of(Key.create(persistResource(ValueObject.create(1, "foo"))));
    assertThat(root.toHydratedString()).contains("foo");
  }
}
