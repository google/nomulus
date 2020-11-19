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

package google.registry.tools;

import static google.registry.testing.DatabaseHelper.createTlds;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Abstract class for commands that construct + send EPP commands.
 *
 * Has an EppToolVerifier member that needs to have all epp messages accounted for before the test
 * has ended.
 *
 * @param <C> the command type
 */
public abstract class EppToolCommandTestCase<C extends EppToolCommand> extends CommandTestCase<C> {

  EppToolVerifier eppVerifier;

  @BeforeEach
  public void beforeEachEppToolCommandTestCase() {
    // Create two TLDs for commands that allow multiple TLDs at once.
    createTlds("tld", "tld2");
    eppVerifier = EppToolVerifier.create(command).expectClientId("NewRegistrar");
  }

  @AfterEach
  public void afterEachEppToolCommandTestCase() throws Exception {
    eppVerifier.verifyNoMoreSent();
  }
}
