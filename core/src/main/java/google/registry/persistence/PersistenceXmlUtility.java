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
import java.net.URL;
import java.util.List;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;
import org.hibernate.jpa.boot.spi.PersistenceXmlParser;

/** Utility class that provides methods to manipulate persistence.xml file. */
public class PersistenceXmlUtility {

  private static final String PERSISTENCE_XML_FILE_PATH = "META-INF/persistence.xml";

  private PersistenceXmlUtility() {}

  /** Returns the {@link PersistenceUnitDescriptor} instance constructed from persistence.xml. */
  public static ParsedPersistenceXmlDescriptor getParsedPersistenceXmlDescriptor() {
    PersistenceXmlParser parser = PersistenceXmlParser.create();
    List<URL> persistenceXmlUrls =
        parser.getClassLoaderService().locateResources(PERSISTENCE_XML_FILE_PATH);
    return parser.parse(persistenceXmlUrls).values().stream()
        .filter(unit -> PersistenceModule.PERSISTENCE_UNIT_NAME.equals(unit.getName()))
        .map(ParsedPersistenceXmlDescriptor.class::cast)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format(
                        "Could not find persistence unit with name %s",
                        PersistenceModule.PERSISTENCE_UNIT_NAME)));
  }

  /** Returns all managed classes defined in persistence.xml. */
  public static ImmutableList<Class<?>> getManagedClasses() {
    return getParsedPersistenceXmlDescriptor().getManagedClassNames().stream()
        .map(
            className -> {
              try {
                return Class.forName(className);
              } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(
                    String.format(
                        "Could not load class with name %s present in persistence.xml", className),
                    e);
              }
            })
        .collect(toImmutableList());
  }
}
