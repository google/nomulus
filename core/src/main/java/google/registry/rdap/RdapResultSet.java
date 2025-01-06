// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import google.registry.model.EppResource;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import java.util.List;

/**
 * RDAP result set.
 *
 * @param resources List of EPP resources.
 * @param incompletenessWarningType Type of warning to display regarding possible incomplete data.
 * @param numResourcesRetrieved Number of resources retrieved from the database in the process of
 *     assembling the data set.
 */
record RdapResultSet<T extends EppResource>(
    ImmutableList<T> resources,
    IncompletenessWarningType incompletenessWarningType,
    int numResourcesRetrieved) {

  static <S extends EppResource> RdapResultSet<S> create(List<S> resources) {
    return create(resources, IncompletenessWarningType.COMPLETE, resources.size());
  }

  static <S extends EppResource> RdapResultSet<S> create(
      List<S> resources,
      IncompletenessWarningType incompletenessWarningType,
      int numResourcesRetrieved) {
    return new RdapResultSet<>(
        ImmutableList.copyOf(resources), incompletenessWarningType, numResourcesRetrieved);
  }
}
