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

package google.registry.rde.imports;

import static google.registry.mapreduce.MapreduceRunner.PARAM_MAP_SHARDS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.VoidWork;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.contact.ContactResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import google.registry.util.SystemClock;
import google.registry.xjc.JaxbFragment;
import google.registry.xjc.rdecontact.XjcRdeContact;
import google.registry.xjc.rdecontact.XjcRdeContactElement;
import javax.inject.Inject;

/**
 * A mapreduce that imports contacts from an escrow file.
 *
 * <p>Specify the escrow file to import with the "path" parameter.
 */
@Action(path = "/_dr/task/importRdeContacts")
public class RdeContactImportAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  protected final MapreduceRunner mrRunner;
  protected final Response response;
  protected final String importBucketName;
  protected final String importFileName;
  protected final Optional<Integer> mapShards;

  @Inject
  public RdeContactImportAction(
      MapreduceRunner mrRunner,
      Response response,
      @Config("rdeImportBucket") String importBucketName,
      @Parameter("path") String importFileName,
      @Parameter(PARAM_MAP_SHARDS) Optional<Integer> mapShards) {
    this.mrRunner = mrRunner;
    this.response = response;
    this.importBucketName = importBucketName;
    this.importFileName = importFileName;
    this.mapShards = mapShards;
  }

  @Override
  public void run() {
    response.sendJavaScriptRedirect(createJobPath(mrRunner
        .setJobName("Import contacts from escrow file")
        .setModuleName("backend")
        .runMapOnly(
            createMapper(),
            ImmutableList.of(createInput()))));
  }

  /**
   * Creates a new {@link RdeContactInput}
   */
  private RdeContactInput createInput() {
    return new RdeContactInput(mapShards, importBucketName, importFileName);
  }

  /**
   * Creates a new {@link RdeContactImportMapper}
   */
  private RdeContactImportMapper createMapper() {
    return new RdeContactImportMapper(importBucketName);
  }

  /** Mapper to import contacts from an escrow file. */
  public static class RdeContactImportMapper
      extends Mapper<JaxbFragment<XjcRdeContactElement>, Void, Void> {

    private static final long serialVersionUID = -7645091075256589374L;

    private final String importBucketName;
    private transient RdeImportUtils importUtils;

    public RdeContactImportMapper(String importBucketName) {
      this.importBucketName = importBucketName;
    }

    private RdeImportUtils getImportUtils() {
      if (importUtils == null) {
        importUtils = createRdeImportUtils();
      }
      return importUtils;
    }

    /**
     * Creates a new instance of RdeImportUtils.
     */
    private RdeImportUtils createRdeImportUtils() {
      return new RdeImportUtils(
          ofy(),
          new SystemClock(),
          importBucketName,
          new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize()));
    }

    @Override
    public void map(JaxbFragment<XjcRdeContactElement> fragment) {
      final XjcRdeContact xjcContact = fragment.getInstance().getValue();
      try {
        logger.infofmt("Converting xml for contact %s", xjcContact.getId());
        // Record number of attempted map operations
        getContext().incrementCounter("contact imports attempted");
        logger.infofmt("Saving contact %s", xjcContact.getId());
        ofy().transact(new VoidWork() {
          @Override
          public void vrun() {
            ContactResource contact =
                XjcToContactResourceConverter.convertContact(xjcContact);
            getImportUtils().importContact(contact);
          }
        });
        // Record number of contacts imported
        getContext().incrementCounter("contacts saved");
        logger.infofmt("Contact %s was imported successfully", xjcContact.getId());
      } catch (ResourceExistsException e) {
        // Record the number of contacts already in the registry
        getContext().incrementCounter("existing contacts skipped");
        logger.infofmt("Contact %s already exists", xjcContact.getId());
      } catch (Exception e) {
        // Record the number of contacts with unexpected errors
        getContext().incrementCounter("contact import errors");
        logger.severefmt(e, "Error importing contact %s; xml=%s", xjcContact.getId(), xjcContact);
      }
    }
  }
}
