// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.truth.TruthUtils.assertNullnessParity;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Correspondence;
import com.google.common.truth.Correspondence.BinaryPredicate;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/** Truth subject for asserting things about ImmutableObjects that are not built in. */
public final class ImmutableObjectSubject extends Subject {

  @Nullable private final ImmutableObject actual;

  protected ImmutableObjectSubject(
      FailureMetadata failureMetadata, @Nullable ImmutableObject actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  public void isEqualExceptFields(@Nullable ImmutableObject expected, String... ignoredFields) {
    if (actual == null) {
      assertThat(expected).isNull();
    } else {
      assertThat(expected).isNotNull();
    }
    if (actual != null) {
      Map<Field, Object> actualFields = filterFields(actual, ignoredFields);
      Map<Field, Object> expectedFields = filterFields(expected, ignoredFields);
      assertThat(actualFields).containsExactlyEntriesIn(expectedFields);
    }
  }

  private static String formatItems(String prefix, Iterator<?> iter) {
    return prefix + Joiner.on(", ").join(iter);
  }

  /**
   * Checks that {@code expected} has the same contents as {@code actual} except for fields that are
   * marked with {@link ImmutableObject.DoNotCompare}.
   *
   * <p>This is used to verify that entities stored in both cloud SQL and Datastore are identical.
   */
  public void isEqualAcrossDatabases(@Nullable ImmutableObject expected) {
    checkObjectAcrossDatabases(
        actual, expected, actual != null ? actual.getClass().getName() : "null");
  }

  // The following "check" methods implement a recursive check of immutable object equality across
  // databases.  All of them function in both assertive and predicate modes: if "path" is
  // provided (not null) then they throw AssertionError's with detailed error messages.  If
  // it is null, they return true for equal objects and false for inequal ones.
  //
  // The reason for this dual-mode behavior is that all of these methods can either be used in the
  // context of a test assertion (in which case we want a detailed error message describing exactly
  // the location in a complex object where a difference was discovered) or in the context of a
  // membership check in a set (in which case we don't care about the specific location of the first
  // difference, we just want to be able to determine if the object "is equal to" another object as
  // efficiently as possible -- see checkSetAcrossDatabase()).

  @VisibleForTesting
  static boolean checkObjectAcrossDatabases(
      @Nullable Object actual, @Nullable Object expected, @Nullable String path) {
    if (Objects.equals(actual, expected)) {
      return true;
    }

    // They're different, do a more detailed comparison.

    // Check for null first
    if (actual == null && expected != null) {
      if (path != null) {
        throw new AssertionError("At " + path + ": expected " + expected + "got null.");
      } else {
        return false;
      }
    } else if (actual != null && expected == null) {
      if (path != null) {
        throw new AssertionError("At " + path + ": expected null, got " + actual);
      } else {
        return false;
      }

      // For immutable objects, we have to recurse since the contained
      // object could have DoNotCompare fields, too.
    } else if (expected instanceof ImmutableObject) {
      // We only verify that actual is an ImmutableObject so we get a good error message instead
      // of a context-less ClassCastException.
      if (!(actual instanceof ImmutableObject)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not an immutable object.");
        } else {
          return false;
        }
      }

      if (!checkImmutableAcrossDatabases(
          (ImmutableObject) actual, (ImmutableObject) expected, path)) {
        return false;
      }
    } else if (expected instanceof Map) {
      if (!(actual instanceof Map)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not a Map.");
        } else {
          return false;
        }
      }

      // We could do better performance-wise by assuming that keys can be compared across
      // databases using .equals() -- then we could just iterate over the entries and verify that
      // the key has the same value in the other set.  However, there is currently no way for us
      // to guard against the invalidation of this assumption, and it's less code to simply reuse
      // the set comparison.  As long as the performance is adequate in the context of a test, it
      // doesn't seem like a good idea to try to optimize this.
      if (!checkSetAcrossDatabases(
          ((Map<?, ?>) actual).entrySet(), ((Map<?, ?>) expected).entrySet(), path, "Map")) {
        return false;
      }
    } else if (expected instanceof Set) {
      if (!(actual instanceof Set)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not a Set.");
        } else {
          return false;
        }
      }

      if (!checkSetAcrossDatabases((Set<?>) actual, (Set<?>) expected, path, "Set")) {
        return false;
      }
    } else if (expected instanceof Collection) {
      if (!(actual instanceof Collection)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not a Collection.");
        } else {
          return false;
        }
      }

      if (!checkListAcrossDatabases((Collection<?>) actual, (Collection<?>) expected, path)) {
        return false;
      }

      // Give Map.Entry special treatment to facilitate the use of Set comparison for verification
      // of Map.
    } else if (expected instanceof Map.Entry) {
      if (!(actual instanceof Map.Entry)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not a Map.Entry.");
        } else {
          return false;
        }
      }

      if (!checkObjectAcrossDatabases(
              ((Map.Entry<?, ?>) actual).getKey(), ((Map.Entry<?, ?>) expected).getKey(), path)
          || !checkObjectAcrossDatabases(
              ((Map.Entry<?, ?>) actual).getValue(),
              ((Map.Entry<?, ?>) expected).getValue(),
              path)) {
        return false;
      }
    } else {
      if (!actual.equals(expected)) {
        if (path != null) {
          throw new AssertionError("At " + path + ": " + actual + " is not equal to " + expected);
        } else {
          return false;
        }
      }
    }

    return true;
  }

  private static boolean checkSetAcrossDatabases(
      Set<?> actual, Set<?> expected, String path, String type) {
    // Unfortunately, we can't just check to see whether one set "contains" all of the elements of
    // the other, as the cross database checks don't require strict equality.  Instead we have to do
    // an N^2 comparison to search for an equivalent element.

    // Objects in expected that aren't in actual.
    Set<Object> missing = new HashSet<>();

    // Objects from actual that have matching elements in equal.
    Set<Object> found = new HashSet<>();

    // Build missing and found.
    for (Object expectedElem : expected) {
      boolean gotMatch = false;
      for (Object actualElem : actual) {
        if (checkObjectAcrossDatabases(actualElem, expectedElem, null)) {
          gotMatch = true;
          found.add(actualElem);
          break;
        }
      }

      if (!gotMatch) {
        if (path == null) {
          return false;
        }
        missing.add(expectedElem);
      }
    }

    // Build a set of all objects in actual that don't have counterparts in expected.
    Set<Object> unexpected = path != null ? new HashSet<>() : null;
    for (Object actualElem : actual) {
      if (!found.contains(actualElem)) {
        if (path != null) {
          unexpected.add(actualElem);
        } else {
          return false;
        }
      }
    }

    if (!missing.isEmpty() || (unexpected != null && !unexpected.isEmpty())) {
      if (path != null) {
        String message = "At " + path + ": " + type + " does not contain the expected contents.";
        if (!missing.isEmpty()) {
          message += formatItems("  It is missing: ", missing.iterator());
        }

        if (!unexpected.isEmpty()) {
          message += formatItems("  It contains additional elements: ", unexpected.iterator());
        }

        throw new AssertionError(message);
      } else {
        return false;
      }
    }

    return true;
  }

  private static boolean checkListAcrossDatabases(
      Collection<?> actual, Collection<?> expected, @Nullable String path) {
    Iterator<?> actualIter = actual.iterator();
    Iterator<?> expectedIter = expected.iterator();
    int index = 0;
    while (actualIter.hasNext() && expectedIter.hasNext()) {
      Object actualItem = actualIter.next();
      Object expectedItem = expectedIter.next();
      if (!checkObjectAcrossDatabases(
          actualItem, expectedItem, path != null ? path + "[" + index + "]" : null)) {
        return false;
      }
      ++index;
    }

    if (actualIter.hasNext()) {
      if (path != null) {
        throw new AssertionError(
            formatItems("At " + path + ": has additional items: ", actualIter));
      } else {
        return false;
      }
    }

    if (expectedIter.hasNext()) {
      if (path != null) {
        throw new AssertionError(formatItems("At " + path + ": missing items: ", expectedIter));
      } else {
        return false;
      }
    }

    return true;
  }

  /** Recursive helper for isEqualAcrossDatabases. */
  private static boolean checkImmutableAcrossDatabases(
      ImmutableObject actual, ImmutableObject expected, String path) {
    Map<Field, Object> actualFields = filterFields(actual, ImmutableObject.DoNotCompare.class);
    Map<Field, Object> expectedFields = filterFields(expected, ImmutableObject.DoNotCompare.class);

    for (Map.Entry<Field, Object> entry : expectedFields.entrySet()) {
      if (!actualFields.containsKey(entry.getKey())) {
        if (path != null) {
          throw new AssertionError("At " + path + ": is missing field " + entry.getKey().getName());
        } else {
          return false;
        }
      }

      // Verify that the field values are the same.  We can use "equals()" as a quick check.
      Object expectedFieldValue = entry.getValue();
      Object actualFieldValue = actualFields.get(entry.getKey());
      if (!checkObjectAcrossDatabases(
          actualFieldValue,
          expectedFieldValue,
          path != null ? path + "." + entry.getKey().getName() : null)) {
        return false;
      }
    }

    // Check for fields in actual that are not in expected.
    for (Map.Entry<Field, Object> entry : actualFields.entrySet()) {
      if (!expectedFields.containsKey(entry.getKey())) {
        if (path != null) {
          throw new AssertionError(
              "At " + path + ": has additional field " + entry.getKey().getName());
        } else {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Checks that the hash value reported by {@code actual} is correct.
   *
   * <p>This is used in the replay tests to ensure that hibernate hasn't modified any fields that
   * are not marked as @Insignificant while loading.
   */
  public void hasCorrectHashValue() {
    assertThat(Arrays.hashCode(actual.getSignificantFields().values().toArray()))
        .isEqualTo(actual.hashCode());
  }

  public static Correspondence<ImmutableObject, ImmutableObject> immutableObjectCorrespondence(
      String... ignoredFields) {
    return Correspondence.from(
        new ImmutableObjectBinaryPredicate(ignoredFields), "has all relevant fields equal to");
  }

  public static SimpleSubjectBuilder<ImmutableObjectSubject, ImmutableObject>
      assertAboutImmutableObjects() {
    return assertAbout(ImmutableObjectSubject::new);
  }

  private static class ImmutableObjectBinaryPredicate
      implements BinaryPredicate<ImmutableObject, ImmutableObject> {

    private final String[] ignoredFields;

    private ImmutableObjectBinaryPredicate(String... ignoredFields) {
      this.ignoredFields = ignoredFields;
    }

    @Override
    public boolean apply(@Nullable ImmutableObject actual, @Nullable ImmutableObject expected) {
      if (actual == null && expected == null) {
        return true;
      }
      if (actual == null || expected == null) {
        return false;
      }
      Map<Field, Object> actualFields = filterFields(actual, ignoredFields);
      Map<Field, Object> expectedFields = filterFields(expected, ignoredFields);
      return Objects.equals(actualFields, expectedFields);
    }
  }

  private static Map<Field, Object> filterFields(
      ImmutableObject original, String... ignoredFields) {
    ImmutableSet<String> ignoredFieldSet = ImmutableSet.copyOf(ignoredFields);
    Map<Field, Object> originalFields = ModelUtils.getFieldValues(original);
    // don't use ImmutableMap or a stream->collect model since we can have nulls
    Map<Field, Object> result = new LinkedHashMap<>();
    for (Map.Entry<Field, Object> entry : originalFields.entrySet()) {
      if (!ignoredFieldSet.contains(entry.getKey().getName())) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  /** Filter out fields with the given annotation. */
  private static Map<Field, Object> filterFields(
      ImmutableObject original, Class<? extends Annotation> annotation) {
    Map<Field, Object> originalFields = ModelUtils.getFieldValues(original);
    // don't use ImmutableMap or a stream->collect model since we can have nulls
    Map<Field, Object> result = new LinkedHashMap<>();
    for (Map.Entry<Field, Object> entry : originalFields.entrySet()) {
      if (!entry.getKey().isAnnotationPresent(annotation)) {

        // Perform any necessary substitutions.
        if (entry.getKey().isAnnotationPresent(ImmutableObject.EmptySetToNull.class)
            && entry.getValue() != null
            && ((Set<?>) entry.getValue()).isEmpty()) {
          result.put(entry.getKey(), null);
        } else {
          result.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return result;
  }
}
