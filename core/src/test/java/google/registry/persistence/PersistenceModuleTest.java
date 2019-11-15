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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests for {@link PersistenceModule}. */
@RunWith(JUnit4.class)
public class PersistenceModuleTest {
  @Rule
  public PostgreSQLContainer database = new PostgreSQLContainer(NomulusPostgreSql.getDockerTag());

  private EntityManagerFactory emf;

  @Before
  public void init() {
    emf =
        PersistenceModule.create(
            database.getJdbcUrl(),
            database.getUsername(),
            database.getPassword(),
            PersistenceModule.providesDefaultDatabaseConfigs());
  }

  @After
  public void destroy() {
    if (emf != null) {
      emf.close();
    }
    emf = null;
  }

  @Test
  public void testConnectToDatabase_success() {
    EntityManager em = emf.createEntityManager();
    assertThat(em.isOpen()).isTrue();
    em.close();
  }
}
