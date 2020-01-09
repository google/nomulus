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

import java.sql.Types;
import java.util.Collection;

/** Abstract Hibernate user type for storing/retrieving String collections. */
public abstract class StringCollectionUserType<T extends Collection<String>>
    extends GenericCollectionUserType<T> {
  public static final int COL_TYPE_CODE = Types.ARRAY;
  public static final String COL_DDL_TYPE_NAME = "text[]";

  @Override
  public int[] sqlTypes() {
    return new int[] {COL_TYPE_CODE};
  }

  @Override
  String getColumnTypeName() {
    return "text";
  }
}
