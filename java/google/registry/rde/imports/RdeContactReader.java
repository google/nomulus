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

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.InputReader;
import google.registry.config.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.contact.ContactResource;
import google.registry.util.FormattingLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.NoSuchElementException;
import javax.annotation.concurrent.NotThreadSafe;

/** Mapreduce {@link InputReader} for reading contacts from escrow files */
@NotThreadSafe
public class RdeContactReader extends InputReader<ContactResource> implements Serializable {

  private static final long serialVersionUID = -3688793834175577691L;

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  final String importBucketName;
  final String importFileName;
  final int offset;
  final int maxResults;

  private int count = 0;

  transient RdeParser parser;

  /**
   * Creates a new instance of {@link RdeParser}
   */
  private RdeParser newParser() {
    GcsUtils utils = new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize());
    GcsFilename filename = new GcsFilename(importBucketName, importFileName);
    InputStream xmlInput = utils.openInputStream(filename);
    try {
      RdeParser parser = new RdeParser(xmlInput);
      // skip the file offset and count
      // if count is greater than 0, the reader has been rehydrated after doing some work.
      // skip any already processed records.
      parser.skipContacts(offset + count);
      return parser;
    } catch (Exception e) {
      logger.severefmt(e, "Error opening rde file %s/%s", importBucketName, importFileName);
      throw new RuntimeException(e);
    }
  }

  public RdeContactReader(
      String importBucketName,
      String importFileName,
      int offset,
      int maxResults) {
    this.importBucketName = importBucketName;
    this.importFileName = importFileName;
    this.offset = offset;
    this.maxResults = maxResults;
  }

  @Override
  public ContactResource next() throws IOException {
    if (count < maxResults) {
      if (parser == null) {
        parser = newParser();
        if (parser.isAtContact()) {
          count++;
          return XjcToContactResourceConverter.convertContact(parser.getContact());
        }
      }
      if (parser.nextContact()) {
        count++;
        return XjcToContactResourceConverter.convertContact(parser.getContact());
      }
    }
    throw new NoSuchElementException();
  }

  @Override
  public void endSlice() throws IOException {
    super.endSlice();
    if (parser != null) {
      parser.close();
    }
  }
}
