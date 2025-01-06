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

package google.registry.model.mark;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.List;

/** A country and region of a country where a mark is protected. */
public class MarkProtection extends ImmutableObject {

  /** The two-character code of the country where the mark is protected. */
  @XmlElement(name = "cc")
  String countryCode;

  /**
   * The name of a city, state, province, or other geographic region of the above country code in
   * which the mark is protected.
   */
  String region;

  /** The two-character codes of the countries of this ruling. */
  @XmlElement(name = "ruling")
  List<String> rulingCountryCodes;

  public String getCountryCode() {
    return countryCode;
  }

  public String getRegion() {
    return region;
  }

  public ImmutableList<String> getRulingCountryCodes() {
    return nullToEmptyImmutableCopy(rulingCountryCodes);
  }
}
