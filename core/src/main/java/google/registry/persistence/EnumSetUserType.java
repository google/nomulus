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

import google.registry.util.TypeUtils.TypeInstantiator;
import java.util.HashSet;
import java.util.Set;

/** Abstract Hibernate user type for storing/retrieving {@link Set<Enum<E>>}. */
public class EnumSetUserType<E extends Enum<E>>
    extends GenericCollectionUserType<Set<E>, E, String> {

  @Override
  Set<E> getNewCollection() {
    return new HashSet<>();
  }

  @Override
  ArrayColumnType getColumnType() {
    return ArrayColumnType.STRING;
  }

  @Override
  public Class returnedClass() {
    return Set.class;
  }

  @Override
  protected E convertToElem(String columnValue) {
    return columnValue == null
        ? null
        : Enum.valueOf(new TypeInstantiator<E>(getClass()) {}.getExactType(), columnValue);
  }

  @Override
  protected String convertToColumn(E elementValue) {
    return elementValue == null ? null : elementValue.toString();
  }
}
