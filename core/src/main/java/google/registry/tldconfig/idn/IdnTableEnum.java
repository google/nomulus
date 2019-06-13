// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tldconfig.idn;

import static com.google.common.io.Resources.readLines;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;

/** Wrapper enum that loads all {@link IdnTable} resources into memory. */
public enum IdnTableEnum {
  EXTENDED_LATIN,
  JA;

  private final IdnTable table;

  IdnTableEnum() {
    this.table = load(Ascii.toLowerCase(name()));
  }

  public IdnTable getTable() {
    return table;
  }

  private static IdnTable load(String name) {
    try {
      URL resource = Resources.getResource(IdnTableEnum.class, name + ".txt");
      return IdnTable.createFrom(name, readLines(resource, UTF_8), LanguageValidator.get(name));
    } catch (IOException e) {
      throw new RuntimeException(e);  // should never happen
    }
  }
}
