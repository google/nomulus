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

package google.registry.ui.server;

import com.google.common.collect.ImmutableBiMap;

/**
 * Bimap of state codes and names for the US Regime.
 *
 * @see <a href="http://statetable.com/">State Table</a>
 */
public final class StateCode {

  public static final ImmutableBiMap<String, String> US_MAP =
      new ImmutableBiMap.Builder<String, String>()
          .put("AL", "Alabama")
          .put("AK", "Alaska")
          .put("AZ", "Arizona")
          .put("AR", "Arkansas")
          .put("CA", "California")
          .put("CO", "Colorado")
          .put("CT", "Connecticut")
          .put("DE", "Delaware")
          .put("FL", "Florida")
          .put("GA", "Georgia")
          .put("HI", "Hawaii")
          .put("ID", "Idaho")
          .put("IL", "Illinois")
          .put("IN", "Indiana")
          .put("IA", "Iowa")
          .put("KS", "Kansas")
          .put("KY", "Kentucky")
          .put("LA", "Louisiana")
          .put("ME", "Maine")
          .put("MD", "Maryland")
          .put("MA", "Massachusetts")
          .put("MI", "Michigan")
          .put("MN", "Minnesota")
          .put("MS", "Mississippi")
          .put("MO", "Missouri")
          .put("MT", "Montana")
          .put("NE", "Nebraska")
          .put("NV", "Nevada")
          .put("NH", "New Hampshire")
          .put("NJ", "New Jersey")
          .put("NM", "New Mexico")
          .put("NY", "New York")
          .put("NC", "North Carolina")
          .put("ND", "North Dakota")
          .put("OH", "Ohio")
          .put("OK", "Oklahoma")
          .put("OR", "Oregon")
          .put("PA", "Pennsylvania")
          .put("RI", "Rhode Island")
          .put("SC", "South Carolina")
          .put("SD", "South Dakota")
          .put("TN", "Tennessee")
          .put("TX", "Texas")
          .put("UT", "Utah")
          .put("VT", "Vermont")
          .put("VA", "Virginia")
          .put("WA", "Washington")
          .put("WV", "West Virginia")
          .put("WI", "Wisconsin")
          .put("WY", "Wyoming")
          .put("DC", "Washington DC")
          .build();

  private StateCode() {}
}
