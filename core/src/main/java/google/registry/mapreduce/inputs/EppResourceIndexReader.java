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

package google.registry.mapreduce.inputs;

import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import java.util.NoSuchElementException;

/** Reader that maps over {@link EppResourceIndex} and returns the index objects themselves. */
class EppResourceIndexReader extends EppResourceBaseReader<EppResourceIndex> {

  private static final long serialVersionUID = -4816383426796766911L;

  public EppResourceIndexReader(Key<EppResourceIndexBucket> bucketKey) {
    // Estimate 1MB of memory for this reader, which is massive overkill.
    // Use an empty set for the filter kinds, which disables filtering.
    super(bucketKey, ONE_MB, ImmutableSet.of());
  }

  /**
   * Called for each map invocation.
   *
   * @throws NoSuchElementException if there are no more elements, as specified in the
   *         {@link InputReader#next} Javadoc.
   */
  @Override
  public EppResourceIndex next() throws NoSuchElementException {
    return nextQueryResult();
  }
}
