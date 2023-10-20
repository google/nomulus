package google.registry.bsa.common;

public enum DownloadJobStage {
  DOWNLOAD,
  MAKE_DIFF,
  APPLY_DIFF,
  START_UPLOADING,
  UPLOAD_UNBLOCKABLE_DOMAINS,
  FINISH_UPLOADING,
  DONE,
  NOP,
  CHECKSUMS_NOT_MATCH;
}
