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

package google.registry.persistence.converter;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.TimedTransitionProperty;
import java.io.Serializable;
import java.util.Map;
import org.joda.time.DateTime;

/**
 * Base JPA converter for {@link TimedTransitionProperty} objects that are stored in a column with
 * data type of hstore in the database.
 */
public abstract class TimedTransitionPropertyConverterBase<V extends Serializable>
    extends StringMapConverterBase<DateTime, V, TimedTransitionProperty<V>> {

  @Override
  protected String convertKeyToString(DateTime key) {
    return key.toString();
  }

  @Override
  protected DateTime convertStringToKey(String string) {
    return DateTime.parse(string);
  }

  @Override
  protected TimedTransitionProperty<V> convertMapToDerivedType(Map<DateTime, V> map) {
    return TimedTransitionProperty.fromValueMap(ImmutableSortedMap.copyOf(map));
  }
}
