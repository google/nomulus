# Copyright 2020 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Data types and utilities common to the other modules in this package."""

import dataclasses
import datetime
import enum
import pathlib
import re
from typing import Any, Optional, Tuple

from google.protobuf import timestamp_pb2


class CannotRollbackError(Exception):
    """Indicates that rollback cannot be done by this tool.

    This error is for situations where rollbacks are either not allowed or
    cannot be planned. Example scenarios include:
    - The target release is incompatible with the SQL schema.
    - The target release has never been deployed to AppEngine.
    - The target release is no longer available, e.g., has been manually
      deleted by the operators.
    - A state-changing call to AppEngine Admin API has failed.

    User must manually fix such problems before trying again to roll back.
    """
    pass


class AppEngineScaling(enum.Enum):
    """Types of scaling schemes supported in AppEngine.

    The value of each name is the property name in the REST API requests and
    responses.
    """

    AUTOMATIC = 'automaticScaling'
    BASIC = 'basicScaling'
    MANUAL = 'manualScaling'


@dataclasses.dataclass(frozen=True)
class VersionKey:
    """Identifier of a deployed version on AppEngine.

    AppEngine versions as deployable units are managed on per-service basis.
    Each instance of this class uniquely identifies an AppEngine version.

    This class implements the __eq__ method so that its equality property
    applies to subclasses by default unless they override it.
    """

    service_id: str
    version_id: str

    def __eq__(self, other):
        return (isinstance(other, VersionKey)
                and self.service_id == other.service_id
                and self.version_id == other.version_id)


@dataclasses.dataclass(frozen=True, eq=False)
class VersionConfig(VersionKey):
    """Rollback-related static configuration of an AppEngine version.

    Contains data found from the application-web.xml for this version.

    Attributes:
        scaling: The scaling scheme of this version. This value determines what
            steps are needed for the rollback. If a version is on automatic
            scaling, we only need to direct traffic to it or away from it. The
            version cannot be started, stopped, or have its number of instances
            updated. If a version is on manual scaling, it not only needs to be
            started or stopped explicitly, its instances need to be updated too
            (to 1, the lowest allowed number) when it is shutdown, and to its
            originally configured number of VM instances when brought up.
        manual_scaling_instances: The originally configured VM instances to use
            for each version that is on manual scaling.
    """

    scaling: AppEngineScaling
    manual_scaling_instances: Optional[int] = None


@dataclasses.dataclass(frozen=True)
class VmInstanceInfo:
    """Information about an AppEngine VM instance."""
    instance_name: str
    start_time: datetime.datetime


def get_nomulus_root() -> str:
    """Finds the current Nomulus root directory.

    Returns:
        The absolute path to the Nomulus root directory.
    """
    for folder in pathlib.Path(__file__).parents:
        if folder.name != 'nomulus':
            continue
        if not folder.joinpath('settings.gradle').exists():
            continue
        with open(folder.joinpath('settings.gradle'), 'r') as file:
            for line in file:
                if re.match(r"^rootProject.name\s*=\s*'nomulus'\s*$", line):
                    return folder.absolute()

    raise RuntimeError(
        'Do not move this file out of the Nomulus directory tree.')


def list_all_pages(func, data_field: str, *args, **kwargs) -> Tuple[Any, ...]:
    """Collects all data items from a paginator-based 'List' API.

    Args:
        func: The GCP API method that supports paged responses.
        data_field: The field in a response object containing the data
            items to be returned. This is guaranteed to be an Iterable
            type.

    Returns: An immutable collection of data items assembled from the
        paged responses.
    """
    result_collector = []
    page_token = None
    while True:
        request = func(*args, pageToken=page_token, **kwargs)
        response = request.execute()
        result_collector.extend(response.get(data_field, []))
        page_token = response.get('nextPageToken')
        if not page_token:
            return tuple(result_collector)


def parse_gcp_timestamp(timestamp: str) -> datetime.datetime:
    """Parses a timestamp string in GCP API to datetime.

    This method uses protobuf's Timestamp class to parse timestamp strings.
    This class is used by GCP APIs to parse timestamp strings, and is tolerant
    to certain cases which can break datetime as of Python 3.8, e.g., the
    trailing 'Z' as timezone, and fractional seconds with number of digits
    other than 3 or 6.

    Args:
        timestamp: A string in RFC 3339 format.

    Returns: A datetime instance.
    """
    ts = timestamp_pb2.Timestamp()
    ts.FromJsonString(timestamp)
    return ts.ToDatetime()


def to_gcp_timestamp(timestamp: datetime.datetime) -> str:
    """Converts a datetime to string.

    This method uses protobuf's Timestamp class to parse timestamp strings.
    This class is used by GCP APIs to parse timestamp strings.

    Args:
        timestamp: The datetime instance to be converted.

    Returns: A string in RFC 3339 format.
    """
    ts = timestamp_pb2.Timestamp()
    ts.FromDatetime(timestamp)
    return ts.ToJsonString()
