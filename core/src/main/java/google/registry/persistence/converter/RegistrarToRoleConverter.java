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

package google.registry.persistence.converter;

import google.registry.model.console.RegistrarRole;
import java.util.Map;
import javax.persistence.Converter;

/** JPA converter for storing / retrieving {@code Map<String, RegistrarRole>} objects. */
@Converter(autoApply = true)
public class RegistrarToRoleConverter
    extends StringMapConverterBase<String, RegistrarRole, Map<String, RegistrarRole>> {

  @Override
  protected String convertKeyToString(String key) {
    return key;
  }

  @Override
  protected String convertValueToString(RegistrarRole value) {
    return value.toString();
  }

  @Override
  protected String convertStringToKey(String string) {
    return string;
  }

  @Override
  protected RegistrarRole convertStringToValue(String string) {
    return RegistrarRole.valueOf(string);
  }

  @Override
  protected Map<String, RegistrarRole> convertMapToDerivedType(Map<String, RegistrarRole> map) {
    return map;
  }
}
