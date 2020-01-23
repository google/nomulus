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

package google.registry.model.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.rde.RdeNamingUtils.makePartialName;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.VerifyException;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import org.joda.time.DateTime;

/**
 * Datastore entity for tracking RDE revisions.
 *
 * <p>This class is used by the RDE staging, upload, and reporting systems to determine the revision
 * that should be used in the generated filename. It also determines whether or not a {@code resend}
 * flag is included in the generated XML.
 */
@Entity
public final class RdeRevision extends ImmutableObject {

  /** String triplet of tld, date, and mode, e.g. {@code soy_2015-09-01_full}. */
  @Id
  String id;

  /**
   * Number of last revision successfully staged to GCS.
   *
   * <p>This values begins at zero upon object creation and thenceforth incremented transactionally.
   */
  int revision;

  /**
   * Returns next revision ID to use when staging a new deposit file for the given triplet.
   *
   * @return {@code 0} for first deposit generation and {@code >0} for resends
   */
  public static int getNextRevision(String tld, DateTime date, RdeMode mode) {
    RdeRevision object =
        ofy().load().type(RdeRevision.class).id(makePartialName(tld, date, mode)).now();
    return object == null ? 0 : object.revision + 1;
  }

  /**
   * Sets the revision ID for a given triplet.
   *
   * <p>This method verifies that the current revision is {@code revision - 1}, or that the object
   * does not exist in Datastore if {@code revision == 0}.
   *
   * @throws IllegalStateException if not in a transaction
   * @throws VerifyException if Datastore state doesn't meet the above criteria
   */
  public static void saveRevision(String tld, DateTime date, RdeMode mode, int revision) {
    checkArgument(revision >= 0, "Negative revision: %s", revision);
    String triplet = makePartialName(tld, date, mode);
    tm().assertInTransaction();
    RdeRevision object = ofy().load().type(RdeRevision.class).id(triplet).now();
    if (revision == 0) {
      verify(object == null, "RdeRevision object already created: %s", object);
    } else {
      verifyNotNull(object, "RDE revision object missing for %s?! revision=%s", triplet, revision);
      verify(object.revision == revision - 1,
          "RDE revision object should be at %s but was: %s", revision - 1, object);
    }
    object = new RdeRevision();
    object.id = triplet;
    object.revision = revision;
    ofy().save().entity(object);
  }
}
