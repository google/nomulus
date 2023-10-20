package google.registry.bsa.persistence;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.common.BlockList;
import google.registry.bsa.common.DownloadJobStage;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.UpdateAutoTimestamp;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

@Entity
@Table(indexes = {@Index(columnList = "creationTime,nextStage")})
@Access(AccessType.FIELD)
public class BsaDownload {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  long jobId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create(null);

  @Column(nullable = false)
  String blockListChecksums = "";

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  DownloadJobStage nextStage;

  BsaDownload() {}

  public long getJobId() {
    return jobId;
  }

  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public DownloadJobStage getNextStage() {
    return nextStage;
  }

  public void setBlockListChecksums(ImmutableMap<BlockList, String> checksums) {
    blockListChecksums = Joiner.on(",").withKeyValueSeparator("=").join(checksums);
  }

  public ImmutableMap<BlockList, String> getChecksums() {
    if (blockListChecksums.isEmpty()) {
      return ImmutableMap.of();
    }
    return Splitter.on(',').withKeyValueSeparator('=').split(blockListChecksums).entrySet().stream()
        .collect(
            toImmutableMap(entry -> BlockList.valueOf(entry.getKey()), entry -> entry.getValue()));
  }
}
