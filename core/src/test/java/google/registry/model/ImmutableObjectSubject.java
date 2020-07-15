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

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Truth subject for asserting things about ImmutableObjects that are not built in. */
public final class ImmutableObjectSubject extends Subject {

  private final ImmutableObject actual;

  protected ImmutableObjectSubject(FailureMetadata failureMetadata, ImmutableObject actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  public void isEqualExceptFields(ImmutableObject expected, String... ignoredFields) {
    Map<Field, Object> actualFields = filterFields(actual, ignoredFields);
    Map<Field, Object> expectedFields = filterFields(expected, ignoredFields);
    assertThat(actualFields).containsExactlyEntriesIn(expectedFields);
  }

  public static SimpleSubjectBuilder<ImmutableObjectSubject, ImmutableObject>
      assertAboutImmutableObjects() {
    return assertAbout(ImmutableObjectSubject::new);
  }

  private static Map<Field, Object> filterFields(ImmutableObject original,
      String... ignoredFields) {
    Map<Field, Object> originalFields = ModelUtils.getFieldValues(original);
    ImmutableSet<String> ignoredFieldSet = ImmutableSet.copyOf(ignoredFields);

    // don't use ImmutableMap or a stream->collect model since we can have nulls
    Map<Field, Object> result = new LinkedHashMap<>();
    for (Map.Entry<Field, Object> entry : originalFields.entrySet()) {
      if (!ignoredFieldSet.contains(entry.getKey().getName())) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }
}
