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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.File;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;
import javax.persistence.AttributeConverter;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;

/** Utility class to export DDL script for given {@link javax.persistence.Entity} classes. */
public class HibernateSchemaExporter {
  // Hibernate proprietary mappings.
  private static final String HIBERNATE_MAPPING_RESOURCES = "META-INF/orm.xml";

  private final String jdbcUrl;
  private final String username;
  private final String password;

  private HibernateSchemaExporter(String jdbcUrl, String username, String password) {
    this.jdbcUrl = jdbcUrl;
    this.username = username;
    this.password = password;
  }

  /** Constructs a {@link HibernateSchemaExporter} instance. */
  public static HibernateSchemaExporter create(String jdbcUrl, String username, String password) {
    return new HibernateSchemaExporter(jdbcUrl, username, password);
  }

  /** Exports DDL script to the {@code outputFile} for the given {@code entityClasses}. */
  public void export(ImmutableList<Class<?>> entityClasses, File outputFile) {
    // Configure Hibernate settings.
    Map<String, String> settings = Maps.newHashMap();
    settings.put(Environment.DIALECT, NomulusPostgreSQLDialect.class.getName());
    settings.put(Environment.URL, jdbcUrl);
    settings.put(Environment.USER, username);
    settings.put(Environment.PASS, password);
    settings.put(Environment.HBM2DDL_AUTO, "none");
    // Register driver explicitly to work around ServiceLoader change after Java 8.
    // Driver self-registration only works if driver is declared in a module.
    settings.put(Environment.DRIVER, "org.postgresql.Driver");
    settings.put(Environment.SHOW_SQL, "true");
    settings.put(
        Environment.PHYSICAL_NAMING_STRATEGY, NomulusNamingStrategy.class.getCanonicalName());

    try (StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder().applySettings(settings).build()) {
      MetadataSources metadata = new MetadataSources(registry);
      metadata.addResource(HIBERNATE_MAPPING_RESOURCES);

      // Note that we need to also add all converters to the Hibernate context because
      // the entity class may use the customized type.
      Stream.concat(entityClasses.stream(), findAllConverters().stream())
          .forEach(metadata::addAnnotatedClass);

      SchemaExport export = new SchemaExport();
      export.setHaltOnError(true);
      export.setFormat(true);
      export.setDelimiter(";");
      export.setOutputFile(outputFile.getAbsolutePath());
      export.createOnly(EnumSet.of(TargetType.SCRIPT), metadata.buildMetadata());
    }
  }

  private ImmutableList<Class<?>> findAllConverters() {
    return PersistenceXmlUtility.getManagedClasses().stream()
        .filter(AttributeConverter.class::isAssignableFrom)
        .collect(toImmutableList());
  }
}
