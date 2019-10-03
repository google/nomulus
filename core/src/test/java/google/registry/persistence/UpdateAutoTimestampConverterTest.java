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

import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.tools.GenerateSqlSchemaCommand.NomulusPostgreSQLDialect;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

@RunWith(JUnit4.class)
public class UpdateAutoTimestampConverterTest {

  public static final int POSTGRESQL_PORT = 5432;

  @ClassRule
  public static PostgreSQLContainer postgres =
      new PostgreSQLContainer()
          .withDatabaseName("postgres")
          .withUsername("postgres")
          .withPassword("domain-registry");

  private SessionFactory sessionFactory;

  public UpdateAutoTimestampConverterTest() {}

  @Before
  public void setUp() {
    String dbHost = postgres.getContainerIpAddress();
    int dbPort = postgres.getMappedPort(POSTGRESQL_PORT);

    Map<String, String> settings = new HashMap<>();

    // TODO(sarahbot) fix when file from pr #282 is added
    settings.put(Environment.DIALECT, NomulusPostgreSQLDialect.class.getName());
    settings.put(
        Environment.URL, "jdbc:postgresql://" + dbHost + ":" + dbPort + "/postgres?useSSL=false");
    settings.put(Environment.USER, "postgres");
    settings.put(Environment.PASS, "domain-registry");
    settings.put(Environment.HBM2DDL_AUTO, "update");

    MetadataSources metadataSources =
        new MetadataSources(new StandardServiceRegistryBuilder().applySettings(settings).build());
    metadataSources.addAnnotatedClass(TestEntity.class);
    sessionFactory = metadataSources.buildMetadata().getSessionFactoryBuilder().build();
  }

  @Test
  public void testTypeConversion() {
    TestEntity ent = new TestEntity("myinst");

    DateTime start = DateTime.now(DateTimeZone.UTC);

    Session ses = sessionFactory.openSession();
    Transaction txn = ses.beginTransaction();
    ses.save(ent);
    txn.commit();

    DateTime end = DateTime.now(DateTimeZone.UTC);

    // Have to evict the object so we can read it back with its datetime.
    ses.evict(ent);

    List<TestEntity> result = ses.createQuery("from TestEntity T where T.id = 'myinst'").list();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).name).isEqualTo("myinst");
    assertThat(result.get(0).uat.getTimestamp()).isAtLeast(start);
    assertThat(result.get(0).uat.getTimestamp()).isAtMost(end);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  public static class TestEntity extends ImmutableObject {

    @Id String name;

    @Convert(converter = UpdateAutoTimestampConverter.class)
    UpdateAutoTimestamp uat;

    public TestEntity() {}

    public TestEntity(String name) {
      this.name = name;
    }
  }
}
