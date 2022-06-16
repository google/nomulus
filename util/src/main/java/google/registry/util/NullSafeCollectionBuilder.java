// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import com.google.common.collect.ImmutableCollection;
import javax.annotation.Nullable;

/**
 * Collection builder that simply ignores requests to add null elements.
 *
 * <p>Useful because we do this in a number of cases.
 */
public class NullSafeCollectionBuilder<E, B extends ImmutableCollection.Builder<E>> {

  private B builder;

  private NullSafeCollectionBuilder(B builder) {
    this.builder = builder;
  }

  public static <E2, B2 extends ImmutableCollection.Builder<E2>>
      NullSafeCollectionBuilder<E2, B2> create(B2 builder) {
    return new NullSafeCollectionBuilder<E2, B2>(builder);
  }

  /** If 'elem' is not null, add it to the builder. */
  public NullSafeCollectionBuilder<E, B> add(@Nullable E elem) {
    if (elem != null) {
      builder.add(elem);
    }
    return this;
  }

  /** Returns the underlying builder. */
  public B getBuilder() {
    return builder;
  }
}
