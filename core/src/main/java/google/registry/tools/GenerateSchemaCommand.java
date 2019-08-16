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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.domain.DomainBase;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;

/**
 * Generates a schema for JPA annotated classes using hibernate.
 *
 * <p>Note that this isn't complete yet, as all of the persistent classes have not yet been
 * converted. After converting a class, a call to "addAnnotatedClass()" for the new class must be
 * added to the code below.
 */
@Parameters(separators = " =", commandDescription = "")
public class GenerateSchemaCommand implements Command {

  public static final int POSTGRESQL_PORT = 5432;

  @Parameter(
      names = {"-o", "--out-file"},
      description = "")
  String outFile;

  @Parameter(
      names = {"-a", "--db-host"},
      description = "Database host name.")
  String databaseHost;

  @Parameter(
      names = {"-p", "--db-port"},
      description = "Database port number.  This defaults to the postgresql default port.")
  int databasePort = POSTGRESQL_PORT;

  @Override
  public void run() {
    Map<String, String> settings = new HashMap<>();
    settings.put("connection.driver_class", "com.mysql.jdbc.Driver");
    settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQL9Dialect");
    settings.put(
        "hibernate.connection.url",
        "jdbc:postgresql://" + databaseHost + ":" + databasePort + "/postgres?useSSL=false");
    settings.put("hibernate.connection.username", "postgres");
    settings.put("hibernate.connection.password", "domain-registry");
    settings.put("hibernate.hbm2ddl.auto", "create");
    settings.put("show_sql", "true");

    MetadataSources metadata =
        new MetadataSources(new StandardServiceRegistryBuilder().applySettings(settings).build());
    metadata.addAnnotatedClass(DomainBase.class);
    SchemaExport schemaExport = new SchemaExport();
    schemaExport.setHaltOnError(true);
    schemaExport.setFormat(true);
    schemaExport.setDelimiter(";");
    schemaExport.setOutputFile(outFile);
    schemaExport.createOnly(EnumSet.of(TargetType.SCRIPT), metadata.buildMetadata());
  }
}
