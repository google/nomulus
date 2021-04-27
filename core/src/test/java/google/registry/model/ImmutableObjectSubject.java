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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.truth.Correspondence;
import com.google.common.truth.Correspondence.BinaryPredicate;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collector;
import javax.annotation.Nullable;

/** Truth subject for asserting things about ImmutableObjects that are not built in. */
public final class ImmutableObjectSubject extends Subject {

  @Nullable private final ImmutableObject actual;

  protected ImmutableObjectSubject(
      FailureMetadata failureMetadata, @Nullable ImmutableObject actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  public void hasFieldsEqualTo(@Nullable ImmutableObject expected) {
    isEqualExceptFields(expected);
  }

  public void isEqualExceptFields(
      @Nullable ImmutableObject expected, Iterable<String> ignoredFields) {
    isEqualExceptFields(expected, Iterables.toArray(ignoredFields, String.class));
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

  /**
   * Checks that {@code expected} has the same contents as {@code actual} except for fields that are
   * marked with {@link ImmutableObject.DoNotCompare}.
   *
   * <p>This is used to verify that entities stored in both cloud SQL and Datastore are identical.
   */
  public void isEqualAcrossDatabases(@Nullable ImmutableObject expected) {
    ComparisonResult result =
        checkObjectAcrossDatabases(
            actual, expected, actual != null ? actual.getClass().getName() : "null");
    if (result.isFailure()) {
      throw new AssertionError(result.getMessage());
    }
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
  static ComparisonResult checkObjectAcrossDatabases(
      @Nullable Object actual, @Nullable Object expected, @Nullable String path) {
    if (Objects.equals(actual, expected)) {
      return ComparisonResult.createSuccess();
    }

    // They're different, do a more detailed comparison.

    // Check for null first (we can assume both variables are not null at this point).
    if (actual == null) {
      return ComparisonResult.createFailure(path, "expected ", expected, "got null.");
    } else if (expected == null) {
      return ComparisonResult.createFailure(path, "expected null, got ", actual);

      // For immutable objects, we have to recurse since the contained
      // object could have DoNotCompare fields, too.
    } else if (expected instanceof ImmutableObject) {
      // We only verify that actual is an ImmutableObject so we get a good error message instead
      // of a context-less ClassCastException.
      if (!(actual instanceof ImmutableObject)) {
        return ComparisonResult.createFailure(path, actual, " is not an immutable object.");
      }

      return checkImmutableAcrossDatabases(
          (ImmutableObject) actual, (ImmutableObject) expected, path);
    } else if (expected instanceof Map) {
      if (!(actual instanceof Map)) {
        return ComparisonResult.createFailure(path, actual, " is not a Map.");
      }

      // This would likely be more efficient if we could assume that keys can be compared across
      // databases using .equals(), however we cannot guarantee key equality so the simplest and
      // most correct way to accomplish this is by reusing the set comparison.
      return checkSetAcrossDatabases(
          ((Map<?, ?>) actual).entrySet(), ((Map<?, ?>) expected).entrySet(), path, "Map");
    } else if (expected instanceof Set) {
      if (!(actual instanceof Set)) {
        return ComparisonResult.createFailure(path, actual, " is not a Set.");
      }

      return checkSetAcrossDatabases((Set<?>) actual, (Set<?>) expected, path, "Set");
    } else if (expected instanceof Collection) {
      if (!(actual instanceof Collection)) {
        return ComparisonResult.createFailure(path, actual, " is not a Collection.");
      }

      return checkListAcrossDatabases((Collection<?>) actual, (Collection<?>) expected, path);
      // Give Map.Entry special treatment to facilitate the use of Set comparison for verification
      // of Map.
    } else if (expected instanceof Map.Entry) {
      if (!(actual instanceof Map.Entry)) {
        return ComparisonResult.createFailure(path, actual, " is not a Map.Entry.");
      }

      // Check both the key and value.  We can always ignore the path here, this should only be
      // called from within a set comparison.
      ComparisonResult result;
      if ((result =
              checkObjectAcrossDatabases(
                  ((Map.Entry<?, ?>) actual).getKey(), ((Map.Entry<?, ?>) expected).getKey(), null))
          .isFailure()) {
        return result;
      }
      if ((result =
              checkObjectAcrossDatabases(
                  ((Map.Entry<?, ?>) actual).getValue(),
                  ((Map.Entry<?, ?>) expected).getValue(),
                  null))
          .isFailure()) {
        return result;
      }
    } else {
      // Since we know that the objects are not equal and since any other types can not be expected
      // to contain DoNotCompare elements, this condition is always a failure.
      return ComparisonResult.createFailure(path, actual, " is not equal to ", expected);
    }

    return ComparisonResult.createSuccess();
  }

  private static ComparisonResult checkSetAcrossDatabases(
      Set<?> actual, Set<?> expected, String path, String type) {
    // Unfortunately, we can't just check to see whether one set "contains" all of the elements of
    // the other, as the cross database checks don't require strict equality.  Instead we have to do
    // an N^2 comparison to search for an equivalent element.

    // Objects in expected that aren't in actual.  We use "identity sets" here and below because we
    // want to keep track of the _objects themselves_ rather than rely upon any overridable notion
    // of equality.
    Set<Object> missing = path != null ? Sets.newIdentityHashSet() : null;

    // Objects from actual that have matching elements in expected.
    Set<Object> found = Sets.newIdentityHashSet();

    // Build missing and found.
    for (Object expectedElem : expected) {
      boolean gotMatch = false;
      for (Object actualElem : actual) {
        if (!checkObjectAcrossDatabases(actualElem, expectedElem, null).isFailure()) {
          gotMatch = true;

          // Add the element to the set of expected elements that were "found" in actual.  If the
          // element matches multiple elements in "expected," we have a basic problem with this
          // kind of set that we'll want to know about.
          if (!found.add(actualElem)) {
            return ComparisonResult.createFailure(
                path, "element ", actualElem, " matches multiple elements in ", expected);
          }

          break;
        }
      }

      if (!gotMatch) {
        if (path == null) {
          return ComparisonResult.createFailure();
        }
        missing.add(expectedElem);
      }
    }

    if (path != null) {
      // Provide a detailed message consisting of any missing or unexpected items.

      // Build a set of all objects in actual that don't have counterparts in expected.
      Set<Object> unexpected =
          actual.stream()
              .filter(actualElem -> !found.contains(actualElem))
              .collect(
                  Collector.of(
                      Sets::newIdentityHashSet,
                      Set::add,
                      (result, values) -> {
                        result.addAll(values);
                        return result;
                      }));

      if (!missing.isEmpty() || !unexpected.isEmpty()) {
        String message = type + " does not contain the expected contents.";
        if (!missing.isEmpty()) {
          message += "  It is missing: " + formatItems(missing.iterator());
        }

        if (!unexpected.isEmpty()) {
          message += "  It contains additional elements: " + formatItems(unexpected.iterator());
        }

        return ComparisonResult.createFailure(path, message);
      }

      // We just need to check if there were any objects in "actual" that were not in "expected"
      // (where "found" is a proxy for "expected").
    } else if (!found.containsAll(actual)) {
      return ComparisonResult.createFailure();
    }

    return ComparisonResult.createSuccess();
  }

  private static ComparisonResult checkListAcrossDatabases(
      Collection<?> actual, Collection<?> expected, @Nullable String path) {
    Iterator<?> actualIter = actual.iterator();
    Iterator<?> expectedIter = expected.iterator();
    int index = 0;
    while (actualIter.hasNext() && expectedIter.hasNext()) {
      Object actualItem = actualIter.next();
      Object expectedItem = expectedIter.next();
      ComparisonResult result =
          checkObjectAcrossDatabases(
              actualItem, expectedItem, path != null ? path + "[" + index + "]" : null);
      if (result.isFailure()) {
        return result;
      }
      ++index;
    }

    if (actualIter.hasNext()) {
      return ComparisonResult.createFailure(
          path, "has additional items: ", formatItems(actualIter));
    }

    if (expectedIter.hasNext()) {
      return ComparisonResult.createFailure(path, "missing items: ", formatItems(expectedIter));
    }

    return ComparisonResult.createSuccess();
  }

  /** Recursive helper for isEqualAcrossDatabases. */
  private static ComparisonResult checkImmutableAcrossDatabases(
      ImmutableObject actual, ImmutableObject expected, String path) {
    Map<Field, Object> actualFields = filterFields(actual, ImmutableObject.DoNotCompare.class);
    Map<Field, Object> expectedFields = filterFields(expected, ImmutableObject.DoNotCompare.class);

    for (Map.Entry<Field, Object> entry : expectedFields.entrySet()) {
      if (!actualFields.containsKey(entry.getKey())) {
        return ComparisonResult.createFailure(path, "is missing field ", entry.getKey().getName());
      }

      // Verify that the field values are the same.
      Object expectedFieldValue = entry.getValue();
      Object actualFieldValue = actualFields.get(entry.getKey());
      ComparisonResult result =
          checkObjectAcrossDatabases(
              actualFieldValue,
              expectedFieldValue,
              path != null ? path + "." + entry.getKey().getName() : null);
      if (result.isFailure()) {
        return result;
      }
    }

    // Check for fields in actual that are not in expected.
    for (Map.Entry<Field, Object> entry : actualFields.entrySet()) {
      if (!expectedFields.containsKey(entry.getKey())) {
        return ComparisonResult.createFailure(
            path, "has additional field ", entry.getKey().getName());
      }
    }

    return ComparisonResult.createSuccess();
  }

  private static String formatItems(Iterator<?> iter) {
    return Joiner.on(", ").join(iter);
  }

  /** Encapsulates success/failure result in recursive comparison with optional error string. */
  static class ComparisonResult {
    boolean succeeded;
    String message;

    private ComparisonResult(boolean succeeded, @Nullable String message) {
      this.succeeded = succeeded;
      this.message = message;
    }

    static ComparisonResult createFailure() {
      return new ComparisonResult(false, null);
    }

    static ComparisonResult createFailure(@Nullable String path, Object... message) {
      return new ComparisonResult(false, "At " + path + ": " + Joiner.on("").join(message));
    }

    static ComparisonResult createSuccess() {
      return new ComparisonResult(true, null);
    }

    String getMessage() {
      checkNotNull(message);
      return message;
    }

    boolean isFailure() {
      return !succeeded;
    }
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
