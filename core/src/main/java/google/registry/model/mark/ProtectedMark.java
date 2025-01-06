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

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;
import org.joda.time.DateTime;

/** Common fields for {@link CourtMark} and {@link TreatyOrStatuteMark}. */
@XmlTransient
public abstract class ProtectedMark extends CommonMarkFields {
  /** The date of protection of the mark. */
  @XmlElement(name = "proDate")
  DateTime protectionDate;

  /**
   * The reference number of the court's opinion for a court mark, or the number of the mark of the
   * treaty or statute.
   */
  @XmlElement(name = "refNum")
  String referenceNumber;

  public DateTime getProtectionDate() {
    return protectionDate;
  }

  public String getReferenceNumber() {
    return referenceNumber;
  }
}
