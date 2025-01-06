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
import org.joda.time.DateTime;

/** Holds information about a registered trademark. */
@XmlType(propOrder = {
    "id",
    "markName",
    "markHolders",
    "contacts",
    "jurisdiction",
    "markClasses",
    "labels",
    "goodsAndServices",
    "applicationId",
    "applicationDate",
    "registrationNumber",
    "registrationDate",
    "expirationDate"})
public class Trademark extends CommonMarkFields {

  /** Two character code of the jurisdiction where the mark was registered. */
  String jurisdiction;

  /** Nice Classification numbers of the mark. */
  @XmlElement(name = "class")
  List<Long> markClasses;

  /** The trademark application id registered in the trademark office. */
  @XmlElement(name = "apId")
  String applicationId;

  /** The date that the trademark was applied for. */
  @XmlElement(name = "apDate")
  DateTime applicationDate;

  /** The trademark registration number registered in the trademark office. */
  @XmlElement(name = "regNum")
  String registrationNumber;

  /** The date the trademark was registered. */
  @XmlElement(name = "regDate")
  DateTime registrationDate;

  /** Expiration date of the trademark. */
  @XmlElement(name = "exDate")
  DateTime expirationDate;

  public String getJurisdiction() {
    return jurisdiction;
  }

  public ImmutableList<Long> getMarkClasses() {
    return nullToEmptyImmutableCopy(markClasses);
  }

  public String getApplicationId() {
    return applicationId;
  }

  public DateTime getApplicationDate() {
    return applicationDate;
  }

  public String getRegistrationNumber() {
    return registrationNumber;
  }

  public DateTime getRegistrationDate() {
    return registrationDate;
  }

  public DateTime getExpirationDate() {
    return expirationDate;
  }
}
