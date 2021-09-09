// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * An Jsonable that can turn itself into a JSON object using reflection.
 *
 * <p>This can only be used to create JSON *objects*, so if your class needs a different JSON type,
 * you'll have to implement Jsonable yourself. (for example, VCards objects are represented as a
 * list rather than an object)
 *
 * <p>You can annotate fields or methods with 0 parameters with {@link JsonElement}, and its value
 * will be "JSONified" and added to the generated JSON object.
 *
 * <p>This implementation is geared towards RDAP replies, and hence has RDAP-specific quirks.
 * Specifically:
 *
 * - Fields with empty arrays are not shown at all
 *
 * - VCards are a built-in special case (Not implemented yet)
 *
 * - DateTime conversion is specifically supported as if it were a primitive
 *
 * - Arrays are considered to be SETS rather than lists, meaning repeated values are removed and the
 *   order isn't guaranteed
 *
 * Usage:
 *
 * {@link JsonableElement}
 * -----------------------
 *
 * <pre>
 * - JsonableElement annotates Members that become JSON object fields:
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement public String a = "value1";
 *   @JsonableElement public String b() {return "value2";}
 * }
 *
 * will result in:
 * {
 *   "a": "value1",
 *   "b": "value2"
 * }
 *
 * - Passing a name to JsonableElement overrides the Member's name. Multiple elements with the same
 *   name is an error (except for lists - see later)
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement("b") public String a = "value1";
 * }
 *
 * will result in:
 * {
 *   "b": "value1"
 * }
 *
 * - the supported object types are String, Boolean, Number, DateTime, Jsonable. In addition,
 *   Iterable and Optional are respected.
 *
 * - An Optional that's empty is skipped, while a present Optional acts exactly like the object it
 *   wraps. Null values are errors.
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement public Optional<String> a = Optional.of("value1");
 *   @JsonableElement public Optional<String> b = Optional.empty();
 * }
 *
 * will result in:
 * {
 *   "a": "value1"
 * }
 *
 * - An Iterable will turn into an array. Multiple Iterables with the same name are merged. Remember
 *   - arrays are treated as "sets" here.
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement("lst") public List<String> a = ImmutableList.of("value1", "value2");
 *   @JsonableElement("lst") public List<String> b = ImmutableList.of("value2", "value3");
 * }
 *
 * will result in:
 * {
 *   "lst": ["value1", "value2", "value3"]
 * }
 *
 * - A single element with a [] after its name is added to the array as if it were wrapped in a list
 *   with one element. Optionals are still respected (an empty Optional is skipped).
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement("lst") public List<String> a = ImmutableList.of("value1", "value2");
 *   @JsonableElement("lst[]") public String b = "value3";
 *   @JsonableElement("lst[]") public Optional<String> c = Optional.empty();
 * }
 *
 * will result in:
 * {
 *   "lst": ["value1", "value2", "value3"]
 * }
 * </pre>
 *
 * {@link RestrictJsonNames}
 * -------------------------
 *
 * <pre>
 * - RestrictJsonNames is a way to prevent typos in the JsonableElement names.
 *
 * - If it annotates a Jsonable class declaration, this class can only be annotated with
 *   JsonableElements with one of the allowed names
 *
 * @RestrictJsonNames({"key", "lst[]"})
 * class Something implements Jsonable {...}
 *
 * means that Something can only be used as an element named "key", OR as an element in an array
 * named "lst".
 *
 * @JsonableElement public Something something; // ERROR
 * @JsonableElement("something") public Something key; // ERROR
 * @JsonableElement public Something key; // OK
 * @JsonableElement("key") public Something something; // OK
 * @JsonableElement("lst") public List<Something> myList; // OK
 * @JsonableElement("lst[]") public Something something; // OK
 *
 * - @RestrictJsonNames({}) means this Jsonable can't be inserted into an AbstractJsonableObject at
 *   all. It's useful for "outer" Jsonable that are to be returned as is, or for
 *   AbstractJsonableObject only used for JsonableElement("*") (Merging - see next)
 * </pre>
 *
 * {@link JsonableElement} with "*" for name - merge instead of sub-object
 * -----------------------------------------------------------------------
 *
 * <pre>
 * The special name "*" means we want to merge the object with the current object instead of having
 * it a value for the name key.
 *
 * THIS MIGHT BE REMOVED LATER, we'll see how it goes. Currently it's only used in one place and
 * might not be worth it.
 *
 * - JsonableElement("*") annotates an AbstractJsonableObject Member that is to be merged with the
 *   current AbstractJsonableObject, instead of being a sub-object
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement public String a = "value1";
 * }
 *
 * class Other extends AbstractJsonableObject {
 *   @JsonableElement("*") public Something something = new Something();
 *   @JsonableElement public String b = "value2";
 * }
 *
 * Other will result in:
 * {
 *   "a": "value1",
 *   "b": "value2"
 * }
 *
 * - Arrays of the same name are merges (remember, they are considered sets so duplicates are
 *   removed), but elements with the same name are an error
 *
 * class Something extends AbstractJsonableObject {
 *   @JsonableElement("lst[]") public String a = "value1";
 * }
 *
 * class Other extends AbstractJsonableObject {
 *   @JsonableElement("*") public Something something = new Something();
 *   @JsonableElement("lst[]") public String b = "value2";
 * }
 *
 * Other will result in:
 * {
 *   "lst": ["value1", "value2"]
 * }
 *
 * - Optionals are still respected. An empty JsonableElement("*") is skipped.
 * </pre>
 */
@SuppressWarnings("InvalidBlockTag")
abstract class AbstractJsonableObject implements Jsonable {

  private static final String ARRAY_NAME_SUFFIX = "[]";

  private static final String MERGE_NAME = "*";

  @Target({ElementType.METHOD, ElementType.FIELD})
  @Retention(RUNTIME)
  @interface JsonableElement {
    String value() default "";
  }

  @Target(ElementType.TYPE)
  @Retention(RUNTIME)
  @interface RestrictJsonNames {
    String[] value();
  }

  @Override
  public final JsonObject toJson() {
    try {
      JsonObjectBuilder builder = new JsonObjectBuilder();

      for (Field field : getAllJsonableElementFields()) {
        JsonableElement jsonableElement = field.getAnnotation(JsonableElement.class);
        Object object;
        try {
          field.setAccessible(true);
          object = field.get(this);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(
              String.format("Error reading value of field '%s'", field), e);
        } finally {
          field.setAccessible(false);
        }
        builder.add(jsonableElement, field, object);
      }

      for (Method method : getAllJsonableElementMethods()) {
        JsonableElement jsonableElement = method.getAnnotation(JsonableElement.class);
        Object object;
        try {
          method.setAccessible(true);
          object = method.invoke(this);
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(
              String.format("Error reading value of method '%s'", method), e);
        } finally {
          method.setAccessible(false);
        }
        builder.add(jsonableElement, method, object);
      }

      return builder.build();
    } catch (Throwable e) {
      throw new JsonableException(
          e, String.format("Error JSONifying %s: %s", this.getClass(), e.getMessage()));
    }
  }

  /**
   * Get all the fields declared on this class.
   *
   * <p>We aren't using {@link Class#getFields} because that would return only the public fields.
   */
  private Iterable<Field> getAllJsonableElementFields() {
    ImmutableList.Builder<Field> builder = new ImmutableList.Builder<>();
    for (Class<?> clazz = this.getClass();
        clazz != null;
        clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        if (!field.isAnnotationPresent(JsonableElement.class)) {
          continue;
        }
        builder.add(field);
      }
    }
    // Sorting for test consistency
    return Ordering.natural().onResultOf(Field::getName).sortedCopy(builder.build());
  }

  /**
   * Get all the methods declared on this class.
   *
   * <p>We aren't using {@link Class#getMethods} because that would return only the public methods.
   */
  private Iterable<Method> getAllJsonableElementMethods() {
    ImmutableList.Builder<Method> builder = new ImmutableList.Builder<>();
    HashSet<String> seenNames = new HashSet<>();
    for (Class<?> clazz = this.getClass();
        clazz != null;
        clazz = clazz.getSuperclass()) {
      for (Method method : clazz.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(JsonableElement.class)) {
          continue;
        }
        checkState(method.getParameterCount() == 0, "Method '%s' must have no arguments", method);
        if (!seenNames.add(method.getName())) {
          // We've seen the same function in a sub-class, so we already added the overridden
          // version.
          continue;
        }
        builder.add(method);
      }
    }
    // Sorting for test consistency
    return Ordering.natural().onResultOf(Method::getName).sortedCopy(builder.build());
  }

  /** Converts an Object to a JsonElement. */
  private static JsonElement toJsonElement(String name, Member member, Object object) {
    if (object instanceof Jsonable) {
      Jsonable jsonable = (Jsonable) object;
      verifyAllowedJsonKeyName(name, member, jsonable.getClass());
      return jsonable.toJson();
    }
    if (object instanceof String) {
      return new JsonPrimitive((String) object);
    }
    if (object instanceof Number) {
      return new JsonPrimitive((Number) object);
    }
    if (object instanceof Boolean) {
      return new JsonPrimitive((Boolean) object);
    }
    if (object instanceof DateTime) {
      // According to RFC 9083 section 3, the syntax of dates and times is defined in RFC3339.
      //
      // According to RFC3339, we should use ISO8601, which is what DateTime.toString does!
      return new JsonPrimitive(((DateTime) object).toString());
    }
    if (object == null) {
      return JsonNull.INSTANCE;
    }
    throw new IllegalArgumentException(
        String.format(
            "Unknows object type '%s' in member '%s'",
            object.getClass(), member));
  }

  /**
   * Finds all the name restrictions on the given class.
   *
   * <p>Empty means there are no restrictions - all names are allowed.
   *
   * <p>If not empty - the resulting list is the allowed names. If the name ends with [], it means
   * the class is an element in a array with this name.
   */
  static Optional<ImmutableSet<String>> getNameRestriction(Class<?> clazz) {
    // Find the first superclass that has an RestrictJsonNames annotation.
    //
    // The reason we don't use @Inherited on the annotation instead is that we want a good error
    // message - we want to tell the user exactly which class was annotated with the
    // RestrictJsonNames if there was an error!
    for (; clazz != null; clazz = clazz.getSuperclass()) {
      RestrictJsonNames restrictJsonFields = clazz.getAnnotation(RestrictJsonNames.class);
      if (restrictJsonFields == null) {
        continue;
      }
      return Optional.of(ImmutableSet.copyOf(restrictJsonFields.value()));
    }
    return Optional.empty();
  }

  /**
   * Makes sure that the name of this element is allowed on the resulting object.
   *
   * <p>A name is not allowed if the object's class (or one of its ancesstors) is annotated
   * with @RestrictJsonNames and the name isn't in that list.
   *
   * <p>If there's no @RestrictJsonNames annotation, all names are allowed.
   */
  static void verifyAllowedJsonKeyName(String name, @Nullable Member member, Class<?> clazz) {
    Optional<ImmutableSet<String>> allowedFieldNames = getNameRestriction(clazz);
    if (!allowedFieldNames.isPresent()) {
      return;
    }
    checkState(
        !allowedFieldNames.get().isEmpty(),
        "Object of type '%s' is annotated with an empty "
            + "RestrictJsonNames, so it can't be on member '%s'",
        clazz,
        member);
    checkState(
        allowedFieldNames.get().contains(name),
        "Object of type '%s' must be named one of ['%s'], but is named '%s' on member '%s'",
        clazz,
        Joiner.on("', '").join(allowedFieldNames.get()),
        name,
        member);
  }

  private static final class JsonObjectBuilder {
    private final JsonObject jsonObject = new JsonObject();
    private final HashMap<String, Member> seenNames = new HashMap<>();

    void add(JsonableElement jsonableElement, Member member, Object object) {
      checkNotNull(
          object, "Member '%s' is null. If you want an optional member - use Optional", member);

      // We ignore any Optional that are empty, as if they didn't exist at all
      if (object instanceof Optional) {
        Optional<?> optional = (Optional<?>) object;
        if (!optional.isPresent()) {
          return;
        }
        object = optional.get();
      }

      // First, if this is a Merge element, merge it with the current elements
      if (MERGE_NAME.equals(jsonableElement.value())) {
        // We want to merge this member with the current member.
        // Recursively get all the ElementData of this member
        checkState(
            object instanceof AbstractJsonableObject,
            "JsonableElement(\"*\") annotating a non-AbstractJsonableObject object in '%s'",
            member);
        AbstractJsonableObject jsonableObject = (AbstractJsonableObject) object;
        mergeWith(jsonableObject.toJson(), member);
        return;
      }

      String name =
          jsonableElement.value().isEmpty() ? member.getName() : jsonableElement.value();

      // If this is an Iterable, return a stream of the inner elements
      if (object instanceof Iterable) {
        checkState(
            !name.endsWith(ARRAY_NAME_SUFFIX),
            "Error in JsonableElement(\"%s\") on '%s': Can't have array of arrays",
            name,
            member);
        // Adds each element individually into the array
        for (Object innerObject : (Iterable<?>) object) {
          addObjectIntoArray(name, member, innerObject);
        }
        return;
      }

      if (name.endsWith(ARRAY_NAME_SUFFIX)) {
        // If the name ends with the "array suffix", add it as if it's an element in an iterable
        addObjectIntoArray(
            name.substring(0, name.length() - ARRAY_NAME_SUFFIX.length()), member, object);
      } else {
        // Otherwise, add the object as-is
        addObject(name, member, object);
      }
    }

    JsonObject build() {
      return jsonObject;
    }

    private void mergeWith(JsonObject otherJsonObject, Member member) {
      for (Map.Entry<String, JsonElement> entry : otherJsonObject.entrySet()) {
        String name = entry.getKey();
        JsonElement otherElement = entry.getValue();

        JsonElement ourElement = jsonObject.get(name);
        if (ourElement == null) {
          jsonObject.add(name, otherElement);
          seenNames.put(name, member);
        } else {
          // Both this and the other object have element with the same name. That's only OK if that
          // element is an array - in which case we merge the arrays.
          checkState((ourElement instanceof JsonArray) && (otherElement instanceof JsonArray),
              "Encountered the same field name '%s' multiple times: '%s' vs. '%s'",
              name,
              member,
              seenNames.get(name));
          ((JsonArray) ourElement).addAll((JsonArray) otherElement);
        }
      }

    }

    private void addObject(String name, Member member, Object object) {
      checkState(
          !jsonObject.has(name),
          "Encountered the same field name '%s' multiple times: '%s' vs. '%s'",
          name,
          member,
          seenNames.get(name));
      seenNames.put(name, member);
      jsonObject.add(name, toJsonElement(name, member, object));
    }

    private void addObjectIntoArray(String name, Member member, Object object) {
      JsonElement innerElement = jsonObject.get(name);
      JsonArray jsonArray;
      if (innerElement == null) {
        jsonArray = new JsonArray();
        jsonObject.add(name, jsonArray);
      } else {
        checkState(innerElement instanceof JsonArray,
          "Encountered the same field name '%s' multiple times: '%s' vs. '%s'",
          name,
          member,
          seenNames.get(name));
        jsonArray = (JsonArray) innerElement;
      }
      seenNames.put(name, member);
      jsonArray.add(toJsonElement(name + ARRAY_NAME_SUFFIX, member, object));
    }
  }

  static class JsonableException extends RuntimeException {

    JsonableException(String message) {
      super(message);
    }

    JsonableException(Throwable e, String message) {
      super(message, e);
    }
  }
}
