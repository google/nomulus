"""Common routines for Nomulus build rules."""

ZIPPER = "@bazel_tools//tools/zip:zipper"

def long_path(ctx, file_):
  """Constructs canonical runfile path relative to TEST_SRCDIR.

  Args:
    ctx: A Skylark rule context.
    file_: A File object that should appear in the runfiles for the test.

  Returns:
    A string path relative to TEST_SRCDIR suitable for use in tests and
    testing infrastructure.
  """
  if file_.short_path.startswith("../"):
    return file_.short_path[3:]
  if file_.owner and file_.owner.workspace_root:
    return file_.owner.workspace_root + "/" + file_.short_path
  return ctx.workspace_name + "/" + file_.short_path

def collect_data_runfiles(targets):
  """Aggregates data runfiles from targets.

  Args:
    targets: A list of Bazel targets.

  Returns:
    A list of Bazel files.
  """
  data = set()
  for target in targets:
    data += _get_runfiles(target, "runfiles")
    data += _get_runfiles(target, "data_runfiles")
  return data

def _get_runfiles(target, attribute):
  runfiles = getattr(target, attribute, None)
  if runfiles:
    return runfiles.files
  return []
