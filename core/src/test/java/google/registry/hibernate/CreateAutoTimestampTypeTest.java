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
package google.registry.hibernate;

import static com.google.common.truth.Truth.assertThat;

import google.registry.model.CreateAutoTimestamp;
import google.registry.testing.FakeClock;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.annotations.Type;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQL95Dialect;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

@RunWith(JUnit4.class)
public class CreateAutoTimestampTypeTest {

  public static final int POSTGRESQL_PORT = 5432;

  @ClassRule public static PostgreSQLContainer postgres =
      new PostgreSQLContainer()
          .withDatabaseName("postgres")
          .withUsername("postgres")
          .withPassword("domain-registry");

  private SessionFactory sessionFactory;

  public CreateAutoTimestampTypeTest() {}

  @Before
  public void setUp() {
    String dbHost = postgres.getContainerIpAddress();
    int dbPort = postgres.getMappedPort(POSTGRESQL_PORT);

    Map<String, String> settings = new HashMap<>();
    settings.put("hibernate.dialect", NomulusPostgreSQLDialect.class.getName());
    settings.put(
        "hibernate.connection.url",
        "jdbc:postgresql://" + dbHost + ":" + dbPort + "/postgres?useSSL=false");
    settings.put("hibernate.connection.username", "postgres");
    settings.put("hibernate.connection.password", "domain-registry");
    settings.put("hibernate.hbm2ddl.auto", "update");
    settings.put("show_sql", "true");

    MetadataSources metadataSources =
        new MetadataSources(new StandardServiceRegistryBuilder().applySettings(settings).build());
    metadataSources.addAnnotatedClass(TestEntity.class);
    sessionFactory = metadataSources.buildMetadata().getSessionFactoryBuilder().build();
  }

  @Test
  public void testTypeConversion() {
    CreateAutoTimestamp ts = CreateAutoTimestamp.create(
        new DateTime(2019, 9, 9, 11, 39));
    TestEntity ent = new TestEntity("myinst", ts);

    Session ses = sessionFactory.openSession();
    Transaction txn = ses.beginTransaction();
    ses.save(ent);
    txn.commit();

    List<TestEntity> result = ses.createQuery("from TestEntity T where T.id = 'myinst'").list();
    assertThat(result).containsExactly(new TestEntity("myinst", ts));

    // Verify that we can load this from a new session.
    ses = sessionFactory.openSession();
    result = ses.createQuery("from TestEntity T where T.id = 'myinst'").list();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).name).isEqualTo("myinst");
    // TODO(mmuller): Fix ZonedDateTime conversion and then remove .getTimestamp().toInstant()
    // below.
    assertThat(result.get(0).cat.getTimestamp().toInstant())
        .isEqualTo(ts.getTimestamp().toInstant());
  }

  /** Nomulus mapping rules for column types in Postgresql. */
  public static class NomulusPostgreSQLDialect extends PostgreSQL95Dialect {
    public NomulusPostgreSQLDialect() {
      super();
      registerColumnType(Types.VARCHAR, "text");
      registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz");
    }
  }

  @Test
  public void testAutoInitialization() {
    CreateAutoTimestamp ts = CreateAutoTimestamp.create(null);
    TestEntity ent = new TestEntity("autoinit", ts);

    DateTime start = DateTime.now();
    Session ses = sessionFactory.openSession();
    Transaction txn = ses.beginTransaction();
    ses.save(ent);
    txn.commit();
    DateTime end = DateTime.now();

    // We have to evict the object from the cache so we can read back the object with its datetime.
    ses.evict(ent);

    List<TestEntity> result = ses.createQuery("from TestEntity where name = 'autoinit'").list();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).cat.getTimestamp())
        .isAtLeast(start);
    assertThat(result.get(0).cat.getTimestamp())
        .isAtMost(end);
  }

  @Entity(name = "TestEntity")  // Override entity name to avoid the nested class reference.
  @Table(name = "TestEntity")
  public static class TestEntity {

    @Id
    String name;

    @Type(type = "google.registry.hibernate.CreateAutoTimestampType")
    CreateAutoTimestamp cat;

    public TestEntity() {}

    public TestEntity(String name, CreateAutoTimestamp cat) {
      this.name = name;
      this.cat = cat;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof TestEntity) {
        TestEntity o = (TestEntity) other;
        return name.equals(o.name) && cat.equals(o.cat);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      return name.hashCode() ^ cat.hashCode();
    }
  }
}
