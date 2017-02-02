// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde.imports;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.io.BaseEncoding;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.RegistryNotFoundException;
import google.registry.model.registry.Registry.TldState;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import javax.inject.Inject;
import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLStreamException;

/**
 * Utility functions for escrow file import.
 */
public class RdeImportUtils {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final Ofy ofy;
  private final Clock clock;
  private final String escrowBucketName;
  private final GcsUtils gcsUtils;

  @Inject
  public RdeImportUtils(
      Ofy ofy, Clock clock, @Config("rdeImportBucket") String escrowBucketName, GcsUtils gcsUtils) {
    this.ofy = ofy;
    this.clock = clock;
    this.gcsUtils = gcsUtils;
    this.escrowBucketName = escrowBucketName;
  }

  private <T extends EppResource> void importEppResource(final T resource, final String type) {
    @SuppressWarnings("unchecked")
    Class<T> resourceClass = (Class<T>) resource.getClass();
    EppResource existing = ofy.load().key(Key.create(resource)).now();
    if (existing != null) {
      // This will roll back the transaction
      // and prevent duplicate history entries from being saved.
      throw new ResourceExistsException();
    }
    ForeignKeyIndex<T> existingForeignKeyIndex =
        ForeignKeyIndex.find(resourceClass, resource.getForeignKey());
    // ForeignKeyIndex should not exist, since existing resource was not found.
    checkState(
        existingForeignKeyIndex == null,
        "New %s resource has existing foreign key index; foreignKey=%s, repoId=%s",
        type,
        resource.getForeignKey(),
        resource.getRepoId());
    ofy.save().entity(resource);
    ofy.save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
    ofy.save().entity(EppResourceIndex.create(Key.create(resource)));
    logger.infofmt(
        "Imported %s resource - ROID=%s, id=%s",
        type, resource.getRepoId(), resource.getForeignKey());
  }

  /**
   * Imports a host from an escrow file.
   *
   * <p>The host will only be imported if it has not been previously imported.
   *
   * <p>If the host is imported, {@link ForeignKeyIndex} and {@link EppResourceIndex} are also
   * created.
   *
   * @return true if the host was created or updated, false otherwise.
   */
  public void importHost(final HostResource resource) {
    importEppResource(resource, "host");
  }

  /**
   * Imports a contact from an escrow file.
   *
   * <p>The contact will only be imported if it has not been previously imported.
   *
   * <p>If the contact is imported, {@link ForeignKeyIndex} and {@link EppResourceIndex} are also
   * created.
   *
   * @return true if the contact was created or updated, false otherwise.
   */
  public void importContact(final ContactResource resource) {
    importEppResource(resource, "contact");
  }

  /**
   * Imports a domain from an escrow file.
   *
   * <p>The domain will only be imported if it has not been previously imported.
   *
   * <p>If the domain is imported, {@link ForeignKeyIndex} and {@link EppResourceIndex} are also
   * created.
   *
   * @return true if the domain was created or updated, false otherwise.
   */
  public void importDomain(final DomainResource resource) {
    importEppResource(resource, "domain");
  }

  /**
   * Validates an escrow file for import.
   *
   * <p>Before an escrow file is imported into the registry, the following conditions must be met:
   *
   * <ul>
   * <li>The TLD must already exist in the registry
   * <li>The TLD must be in the PREDELEGATION state
   * <li>Each registrar must already exist in the registry
   * <li>Each IDN table referenced must already exist in the registry
   * </ul>
   *
   * <p>If any of the above conditions is not true, an {@link IllegalStateException} will be thrown.
   *
   * @param escrowFilePath Path to the escrow file to validate
   * @throws IOException If the escrow file cannot be read
   * @throws IllegalArgumentException if the escrow file cannot be imported
   */
  public void validateEscrowFileForImport(String escrowFilePath) throws IOException {
    // TODO (wolfgang): Add validation method for IDN tables
    try (InputStream input =
        gcsUtils.openInputStream(new GcsFilename(escrowBucketName, escrowFilePath))) {
      try (RdeParser parser = new RdeParser(input)) {
        // validate that tld exists and is in PREDELEGATION state
        String tld = parser.getHeader().getTld();
        try {
          Registry registry = Registry.get(tld);
          TldState currentState = registry.getTldState(clock.nowUtc());
          checkArgument(
              currentState == TldState.PREDELEGATION,
              String.format("Tld '%s' is in state %s and cannot be imported", tld, currentState));
        } catch (RegistryNotFoundException e) {
          throw new IllegalArgumentException(
              String.format("Tld '%s' not found in the registry", tld));
        }
        // validate that all registrars exist
        while (parser.nextRegistrar()) {
          XjcRdeRegistrar registrar = parser.getRegistrar();
          if (Registrar.loadByClientId(registrar.getId()) == null) {
            throw new IllegalArgumentException(
                String.format("Registrar '%s' not found in the registry", registrar.getId()));
          }
        }
      } catch (XMLStreamException | JAXBException e) {
        throw new IllegalArgumentException(
            String.format("Invalid XML file: '%s'", escrowFilePath), e);
      }
    }
  }

  /**
   * Generates a random {@link Trid} for rde import.
   */
  public static Trid generateTridForImport() {
    // Client trids must be a token between 3 and 64 characters long
    // Base64 encoded UUID string meets this requirement
    return Trid
        .create("Import__" + BaseEncoding.base64().encode(UUID.randomUUID().toString().getBytes()));
  }
}
