The files here are directly lifted
from [googleapis/java-storage-nio](https://github.com/googleapis/java-storage-nio/tree/master/google-cloud-nio/src/main/java/com/google/cloud/storage/contrib/nio/testing)
. They are needed because the `StorageOptions` returned from `LocalStorageHelper.getOptions()`
should be serializable for the `GcsUtils` class itself to be serializable in tests, but is not. The
bug is [fixed](https://github.com/googleapis/java-storage-nio/pull/606) upstream. However, the
current released package does not contain the fix yet.

They are not put under `common/testing` because we do not want to introduce a dependency on the
testing configuration from core.