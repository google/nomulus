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

package google.registry.testing.truth;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.truth.Truth;
import javax.annotation.Nullable;

/** Utils class containing helper functions for {@link Truth}. */
public class TruthUtils {

  /** Asserts that both of the given objects are either null or nonnull. */
  public static void assertNullnessParity(@Nullable Object thisObj, @Nullable Object thatObj) {
    if (thisObj == null) {
      assertWithMessage("Expects both objects are null but thatObj is not null")
          .that(thatObj)
          .isNull();
    } else {
      assertWithMessage("Expects both objects are not null but thatObj is null")
          .that(thatObj)
          .isNotNull();
    }
  }

  /** Asserts that both of the given objects are either null or nonnull. */
  public static void assertNullnessParity(
      @Nullable Object thisObj, @Nullable Object thatObj, String errorMessage) {
    if (thisObj == null) {
      assertWithMessage(errorMessage).that(thatObj).isNull();
    } else {
      assertWithMessage(errorMessage).that(thatObj).isNotNull();
    }
  }
}
