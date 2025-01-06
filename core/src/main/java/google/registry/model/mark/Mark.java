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
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

/**
 * Information about one or more marks.
 *
 * <p>A mark is a term for a label with some sort of legally protected status. The most well known
 * version is a registered trademark, but marks can also be derived from court opinions, treaties,
 * or statutes.
 */
@XmlRootElement(name = "mark")
public class Mark extends ImmutableObject {

  /** Marks derived from a registered trademark. */
  @XmlElement(name = "trademark")
  List<Trademark> trademarks;

  /** Marks dervied from a treaty or statue. */
  @XmlElement(name = "treatyOrStatute")
  List<TreatyOrStatuteMark> treatyOrStatuteMarks;

  /** Marks dervied from a court opinion. */
  @XmlElement(name = "court")
  List<CourtMark> courtMarks;

  public ImmutableList<Trademark> getTrademarks() {
    return nullToEmptyImmutableCopy(trademarks);
  }

  public ImmutableList<TreatyOrStatuteMark> getTreatyOrStatuteMarks() {
    return nullToEmptyImmutableCopy(treatyOrStatuteMarks);
  }

  public ImmutableList<CourtMark> getCourtMarks() {
    return nullToEmptyImmutableCopy(courtMarks);
  }
}
