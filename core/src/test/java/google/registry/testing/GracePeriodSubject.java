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
import google.registry.model.domain.GracePeriod;
import javax.annotation.Nullable;

/** Truth subject for asserting things about {@link GracePeriod} that are not built in. */
public class GracePeriodSubject extends Subject {

  @Nullable private final GracePeriod actual;

  /**
   * Constructor for use by subclasses. If you want to create an instance of this class itself, call
   * {@link Subject#check(String, Object ..) check(...)}{@code .that(actual)}.
   */
  protected GracePeriodSubject(FailureMetadata metadata, @Nullable GracePeriod actual) {
    super(metadata, actual);
    this.actual = actual;
  }

  public static SimpleSubjectBuilder<GracePeriodSubject, GracePeriod> assertAboutGracePeriods() {
    return assertAbout(GracePeriodSubject::new);
  }

  public void isEqualExceptId(@Nullable GracePeriod expected) {
    if (actual == null) {
      assertThat(expected).isNull();
    } else {
      assertThat(expected).isNotNull();
    }
    if (actual != null) {
      assertAboutImmutableObjects().that(actual).isEqualExceptFields(expected, "gracePeriodId");
    }
  }
}
