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

package google.registry.backup;

import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.model.ImmutableObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;

/** Utilities for working with backups. */
public class BackupUtils {

  /** Keys for user metadata fields on commit log files in GCS. */
  public static final class GcsMetadataKeys {

    private GcsMetadataKeys() {}

    public static final String NUM_TRANSACTIONS = "num_transactions";
    public static final String LOWER_BOUND_CHECKPOINT = "lower_bound_checkpoint";
    public static final String UPPER_BOUND_CHECKPOINT = "upper_bound_checkpoint";
  }

  /**
   * Converts the given {@link ImmutableObject} to a raw Datastore entity and write it to an
   * {@link OutputStream} in delimited protocol buffer format.
   */
  static void serializeEntity(ImmutableObject entity, OutputStream stream) throws IOException {
    EntityTranslator.convertToPb(auditedOfy().save().toEntity(entity)).writeDelimitedTo(stream);
  }

  /**
   * Return an iterator of {@link ImmutableObject} instances deserialized from the given stream.
   *
   * <p>This parses out delimited protocol buffers for raw Datastore entities and then Ofy-loads
   * those as {@link ImmutableObject}.
   *
   * <p>The iterator reads from the stream on demand, and as such will fail if the stream is closed.
   */
  public static Iterator<ImmutableObject> createDeserializingIterator(final InputStream input) {
    return new AbstractIterator<ImmutableObject>() {
      @Override
      protected ImmutableObject computeNext() {
        EntityProto proto = new EntityProto();
        if (proto.parseDelimitedFrom(input)) { // False means end of stream; other errors throw.
          return auditedOfy().load().fromEntity(EntityTranslator.createFromPb(proto));
        }
        return endOfData();
      }
    };
  }

  public static ImmutableList<ImmutableObject> deserializeEntities(byte[] bytes) {
    return ImmutableList.copyOf(createDeserializingIterator(new ByteArrayInputStream(bytes)));
  }
}
