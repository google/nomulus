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
import jakarta.xml.bind.annotation.XmlTransient;
import java.util.List;

/**
 * Common fields shared amongst all mark instances.
 *
 * @see CourtMark
 * @see Trademark
 * @see TreatyOrStatuteMark
 */
@XmlTransient
public abstract class CommonMarkFields extends ImmutableObject {

  /**
   * The id of the mark. The value is a concatenation of the local identifier, followed by a hyphen,
   * followed by the issuer identifier.
   */
  String id;

  /** The mark text string. */
  String markName;

  /** Information about the holder of the mark. */
  @XmlElement(name = "holder")
  List<MarkHolder> markHolders;

  /** Contact information for the representative of the mark registration. */
  @XmlElement(name = "contact")
  List<MarkContact> contacts;

  /** List of A-labels that correspond to the mark name. */
  @XmlElement(name = "label")
  List<String> labels;

  /** The full description of goods and services mentioned in the mark registration document. */
  String goodsAndServices;

  public String getId() {
    return id;
  }

  public String getMarkName() {
    return markName;
  }

  public ImmutableList<MarkHolder> getMarkHolders() {
    return nullToEmptyImmutableCopy(markHolders);
  }

  public ImmutableList<MarkContact> getContacts() {
    return nullToEmptyImmutableCopy(contacts);
  }

  public ImmutableList<String> getLabels() {
    return nullToEmptyImmutableCopy(labels);
  }

  public String getGoodsAndServices() {
    return goodsAndServices;
  }
}
