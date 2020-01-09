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

package google.registry.persistence;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.function.Supplier;

/** Abstract Hibernate user type for storing/retrieving {@link List<String>}. */
public class StringListUserType extends StringCollectionUserType<List<String>> {

  @Override
  protected Supplier<List<String>> getCollectionSupplier() {
    return Lists::newArrayList;
  }
}
