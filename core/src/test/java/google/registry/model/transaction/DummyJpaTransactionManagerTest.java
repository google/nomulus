// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.transaction;

import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** JUnit test for {@link DummyJpaTransactionManager} */
@RunWith(JUnit4.class)
public class DummyJpaTransactionManagerTest {

  @Test
  public void throwsExceptionWhenAnyMethodIsInvoked() {
    assertThrows(UnsupportedOperationException.class, () -> jpaTm().transact(() -> null));
    assertThrows(UnsupportedOperationException.class, () -> jpaTm().getTransactionTime());
  }
}
