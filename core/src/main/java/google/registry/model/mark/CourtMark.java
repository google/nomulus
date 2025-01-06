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
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.List;

/** Holds information about a mark derived from a court opinion. */
@XmlType(propOrder = {
    "id",
    "markName",
    "markHolders",
    "contacts",
    "labels",
    "goodsAndServices",
    "referenceNumber",
    "protectionDate",
    "countryCode",
    "regions",
    "courtName"})
public class CourtMark extends ProtectedMark {

  /** The two-character code of the country where the court is located. */
  @XmlElement(name = "cc")
  String countryCode;

  /**
   * The name of a city, state, province, or other geographic region of the above country code in
   * which the mark is protected.
   */
  @XmlElement(name = "region")
  List<String> regions;

  /** The name of the court. */
  String courtName;

  public String getCountryCode() {
    return countryCode;
  }

  public ImmutableList<String> getRegions() {
    return nullToEmptyImmutableCopy(regions);
  }

  public String getCourtName() {
    return courtName;
  }
}
