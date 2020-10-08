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

package google.registry.testing;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ImmutableObjectSubject.filterFields;
import static google.registry.testing.TruthHelper.assertBothNullOrNonnull;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import google.registry.model.domain.GracePeriodBase;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Truth subject for asserting things about a collection of {@link GracePeriodBase} that are not
 * built in.
 */
public class GracePeriodsSubject extends Subject {

  @Nullable private final Collection<? extends GracePeriodBase> actual;

  /**
   * Constructor for use by subclasses. If you want to create an instance of this class itself, call
   * {@link Subject#check(String, Object ..) check(...)}{@code .that(actual)}.
   */
  protected GracePeriodsSubject(
      FailureMetadata metadata, @Nullable Collection<? extends GracePeriodBase> actual) {
    super(metadata, actual);
    this.actual = actual;
  }

  /** Creates a {@link SimpleSubjectBuilder} for {@link GracePeriodsSubject}. */
  public static SimpleSubjectBuilder<GracePeriodsSubject, Collection<? extends GracePeriodBase>>
      assertAboutGracePeriods() {
    return assertAbout(GracePeriodsSubject::new);
  }

  /**
   * Asserts that this collection of {@link GracePeriodBase} contains the exact given entities, when
   * comparing the entities, {@link GracePeriodBase#gracePeriodId} are ignored.
   */
  public void containsExactlyExceptId(@Nullable GracePeriodBase... expected) {
    assertBothNullOrNonnull(actual, expected);
    if (actual != null) {
      List<GracePeriodBase> expectedList = Arrays.asList(expected);
      assertWithMessage("Missing GracePeriod in the actual collection: ")
          .that(containsAll(actual, expectedList))
          .isEmpty();
      assertWithMessage("Missing GracePeriod in the expected collection: ")
          .that(containsAll(expectedList, actual))
          .isEmpty();
    }
  }

  private static ImmutableList<GracePeriodBase> containsAll(
      Iterable<? extends GracePeriodBase> thisIterable,
      Iterable<? extends GracePeriodBase> thatIterable) {
    ImmutableList.Builder<GracePeriodBase> missingInstances = new ImmutableList.Builder<>();
    for (GracePeriodBase thatInstance : thatIterable) {
      boolean contains = false;
      for (GracePeriodBase thisInstance : thisIterable) {
        Map<Field, Object> thisFields = filterFields(thisInstance, "gracePeriodId");
        Map<Field, Object> thatFields = filterFields(thatInstance, "gracePeriodId");
        if (thisFields.equals(thatFields)) {
          contains = true;
          break;
        }
      }
      if (!contains) {
        missingInstances.add(thatInstance);
      }
    }
    return missingInstances.build();
  }
}
