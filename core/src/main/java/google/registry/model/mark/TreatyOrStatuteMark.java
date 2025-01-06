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

/** Information about a mark derived from a treaty or statute. */
@XmlType(propOrder = {
    "id",
    "markName",
    "markHolders",
    "contacts",
    "markProtections",
    "labels",
    "goodsAndServices",
    "referenceNumber",
    "protectionDate",
    "title",
    "executionDate"})
public class TreatyOrStatuteMark extends ProtectedMark {

  /** A list of countries and region of the country where the mark is protected. */
  @XmlElement(name = "protection")
  List<MarkProtection> markProtections;

  /** The title of the treaty or statute. */
  String title;

  /** Execution date of the treaty or statute. */
  @XmlElement(name = "execDate")
  DateTime executionDate;

  public ImmutableList<MarkProtection> getMarkProtections() {
    return nullToEmptyImmutableCopy(markProtections);
  }

  public String getTitle() {
    return title;
  }

  public DateTime getExecutionDate() {
    return executionDate;
  }
}
