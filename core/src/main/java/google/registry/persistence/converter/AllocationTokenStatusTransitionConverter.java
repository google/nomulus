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

import google.registry.model.domain.token.AllocationToken.TokenStatus;
import javax.persistence.Converter;

/** JPA converter for storing/retrieving {@code TimedTransitionProperty<TokenStatus>} objects. */
@Converter(autoApply = true)
public class AllocationTokenStatusTransitionConverter
    extends TimedTransitionPropertyConverterBase<TokenStatus> {

  @Override
  protected String convertValueToString(TokenStatus value) {
    return value.name();
  }

  @Override
  protected TokenStatus convertStringToValue(String string) {
    return TokenStatus.valueOf(string);
  }
}
