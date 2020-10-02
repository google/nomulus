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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import google.registry.model.domain.GracePeriodBase;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import javax.annotation.Nullable;

public class GracePeriodCollectionSubject extends Subject {

  @Nullable private final Collection<? extends GracePeriodBase> actual;

  /**
   * Constructor for use by subclasses. If you want to create an instance of this class itself, call
   * {@link Subject#check(String, Object ..) check(...)}{@code .that(actual)}.
   */
  protected GracePeriodCollectionSubject(
      FailureMetadata metadata, @Nullable Collection<? extends GracePeriodBase> actual) {
    super(metadata, actual);
    this.actual = actual;
  }

  public static SimpleSubjectBuilder<
          GracePeriodCollectionSubject, Collection<? extends GracePeriodBase>>
      assertAboutGracePeriodCollection() {
    return assertAbout(GracePeriodCollectionSubject::new);
  }

  public void containsExactlyExceptId(@Nullable GracePeriodBase... expected) {
    if (actual == null) {
      assertThat(expected).isNull();
    } else {
      assertThat(expected).isNotNull();
    }
    if (actual != null) {
      assertThat(actual).hasSize(expected.length);
      Iterator<? extends GracePeriodBase> actualIte = actual.iterator();
      Iterator<? extends GracePeriodBase> expectedIte = Arrays.asList(expected).iterator();
      while (actualIte.hasNext()) {
        assertAboutImmutableObjects()
            .that(actualIte.next())
            .isEqualExceptFields(expectedIte.next(), "gracePeriodId");
      }
    }
  }
}
