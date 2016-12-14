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

import static com.google.common.math.IntMath.divide;
import static java.math.RoundingMode.CEILING;
import static java.math.RoundingMode.FLOOR;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import google.registry.config.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.contact.ContactResource;
import google.registry.rde.imports.RdeParser.RdeHeader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A MapReduce {@link Input} that imports {@link ContactResource} objects from an escrow file.
 *
 * <p>If a mapShards parameter has been specified, up to that many readers will be created
 * so that each map shard has one reader. If a mapShards parameter has not been specified, a
 * default number of readers will be created.
 */
public class RdeContactInput extends Input<ContactResource> {

  private static final long serialVersionUID = -366966393494008712L;
  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  /**
   * Default number of readers if map shards are not specified.
   */
  private static final int DEFAULT_READERS = 50;

  /**
   * Minimum number of records per reader.
   */
  private static final int MINIMUM_RECORDS_PER_READER = 100;

  /**
   * Optional argument to explicitly specify the number of readers.
   */
  private final int numReaders;
  private final String importBucketName;
  private final String importFileName;

  /**
   * Creates a new {@link RdeContactInput}
   *
   * @param mapShards Number of readers that should be created
   * @param importBucketName Name of GCS bucket for escrow file imports
   * @param importFileName Name of escrow file in GCS
   */
  public RdeContactInput(Optional<Integer> mapShards, String importBucketName,
      String importFileName) {
    this.numReaders = mapShards.or(DEFAULT_READERS);
    this.importBucketName = importBucketName;
    this.importFileName = importFileName;
  }

  @Override
  public List<? extends InputReader<ContactResource>> createReaders() throws IOException {
    int numReaders = this.numReaders;
    RdeHeader header = newParser().getHeader();
    int numberOfContacts = header.getContactCount().intValue();
    if (numberOfContacts / numReaders < MINIMUM_RECORDS_PER_READER) {
      numReaders = divide(numberOfContacts, MINIMUM_RECORDS_PER_READER, FLOOR);
      // use at least one reader
      numReaders = Math.max(numReaders, 1);
    }
    ImmutableList.Builder<RdeContactReader> builder = new ImmutableList.Builder<>();
    int contactsPerReader =
        Math.max(MINIMUM_RECORDS_PER_READER, divide(numberOfContacts, numReaders, CEILING));
    int offset = 0;
    for (int i = 0; i < numReaders; i++) {
      builder = builder.add(newReader(offset, contactsPerReader));
      offset += contactsPerReader;
    }
    return builder.build();
  }

  /**
   * Creates a new instance of {@link RdeContactReader}
   */
  private RdeContactReader newReader(int offset, int maxResults) {
    return new RdeContactReader(importBucketName, importFileName, offset, maxResults);
  }

  /**
   * Creates a new instance of {@link RdeParser}
   */
  private RdeParser newParser() {
    GcsUtils utils = new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize());
    GcsFilename filename = new GcsFilename(importBucketName, importFileName);
    try (InputStream xmlInput = utils.openInputStream(filename)) {
      return new RdeParser(xmlInput);
    } catch (Exception e) {
      throw new InitializationException(
          String.format("Error opening rde file %s/%s", importBucketName, importFileName), e);
    }
  }

  /**
   * Thrown when the input cannot initialize properly.
   */
  private static class InitializationException extends RuntimeException {

    public InitializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
