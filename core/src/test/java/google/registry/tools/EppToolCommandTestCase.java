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

import static google.registry.testing.DatastoreHelper.createTlds;

import org.junit.After;
import org.junit.Before;

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

  @Before
  public void init() {
    // Create two TLDs for commands that allow multiple TLDs at once.
    createTlds("tld", "tld2");
    eppVerifier = EppToolVerifier.create(command).expectClientId("NewRegistrar");
  }

  @After
  public void cleanup() throws Exception {
    eppVerifier.verifyNoMoreSent();
  }
}
