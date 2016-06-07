// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.rde;

import com.google.appengine.tools.cloudstorage.GcsFilename;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.config.ConfigModule.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.RegistryNotFoundException;
import google.registry.model.registry.Registry.TldState;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import org.joda.time.DateTime;

import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

/**
 * Utility functions for escrow file import.
 */
public final class RdeImportUtils {

  private final String escrowBucketName;
  private final Ofy ofy;
  private final GcsUtils gcsUtils;

  @Inject
  public RdeImportUtils(
      @Config("rdeImportBucket") String escrowBucketName, Ofy ofy, GcsUtils gcsUtils) {
    this.escrowBucketName = escrowBucketName;
    this.ofy = ofy;
    this.gcsUtils = gcsUtils;
  }

  /**
   * Imports a contact from an escrow file.
   *
   * <p>The contact will only be imported if one of the following two conditions is satisfied:
   * <ul>
   * <li>The contact has not been previously imported
   * <li>The previously imported contact has an older eppUpdateTime than the new one.
   * </ul>
   *
   * <p>If the contact is imported, ForeignKeyIndex and EppResourceIndex are also updated.
   *
   * @return true if the contact was created or updated, false otherwise.
   */
  public boolean importContact(final ContactResource resource) {
    return ofy.transact(new Work<Boolean>() {

      @Override
      public Boolean run() {
        ContactResource existing = ofy.load().key(Key.create(resource)).now();
        if (existing == null
            || existing.getLastEppUpdateTime().isBefore(resource.getLastEppUpdateTime())) {
          ofy.save().entity(resource);
          ofy.save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
          ofy.save().entity(EppResourceIndex.create(Key.create(resource)));
          return true;
        }
        return false;
      }
    });
  }

  /**
   * Validates an escrow file for import.
   *
   * <p>Before an escrow file is imported into the registry, the following conditions must be met:
   * <ul>
   *    <li>The TLD must already exist in the registry</li>
   *    <li>The TLD must be in the PREDELEGATION state</li>
   *    <li>Each registrar must already exist in the registry</li>
   *    <li>Each IDN table referenced must already exist in the registry</li>
   * </ul>
   * <p>If any of the above conditions is not true, an {@link IllegalStateException} will be
   * thrown.
   *
   * @param escrowFilePath Path to the escrow file to validate
   * @throws IOException If the escrow file cannot be read
   * @throws IllegalArgumentException if the escrow file cannot be imported
   */
  public void validateEscrowFileForImport(final String escrowFilePath) throws IOException {
    // TODO: Add validation method for IDN tables
    try (InputStream input =
        gcsUtils.openInputStream(new GcsFilename(escrowBucketName, escrowFilePath))) {
      try {
        RdeParser parser = new RdeParser(input);
        // validate that tld exists and is in PREDELEGATION state
        String tld = parser.getHeader().getTld();
        try {
          Registry registry = Registry.get(tld);
          TldState currentState = registry.getTldState(DateTime.now());
          if (currentState != TldState.PREDELEGATION) {
            throw new IllegalArgumentException(
                String.format("Tld '%s' is in state %s and cannot be imported", tld, currentState));
          }
        } catch (RegistryNotFoundException e) {
          throw new IllegalArgumentException(
              String.format("Tld '%s' not found in the registry", tld));
        }
        // validate that all registrars exist
        while(parser.nextRegistrar()) {
          XjcRdeRegistrar registrar = parser.getRegistrar();
          if (Registrar.loadByClientId(registrar.getId()) == null) {
            throw new IllegalArgumentException(
                String.format("Registrar '%s' not found in the registry", registrar.getId()));
          }
        }
      } catch (XMLStreamException e) {
        throw new IllegalArgumentException(
            String.format("Invalid XML file: '%s'", escrowFilePath), e);
      }
    }
  }
}
