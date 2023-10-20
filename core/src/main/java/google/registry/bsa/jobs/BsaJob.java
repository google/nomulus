package google.registry.bsa.jobs;

import com.google.common.collect.ImmutableMap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.common.DownloadJobStage;
import google.registry.bsa.persistence.BsaDownload;
import java.util.Optional;

public abstract class BsaJob {

  public abstract BsaDownload job();

  public abstract Optional<BsaDownload> previousJob();

  public abstract boolean forceUpdate();

  public abstract void downloadsIgnored();

  public abstract void updateJobStageToNop(ImmutableMap<BlockList, String> checksums);

  public abstract void updateJobStage(DownloadJobStage stage);
}
