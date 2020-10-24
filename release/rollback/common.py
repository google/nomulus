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
"""Declares data types that describe AppEngine services and versions."""

import dataclasses
import enum
import pathlib
import re
from typing import Optional


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
        manual_scaling_instances: The originally configure VM instances to use
            for each version that is on manual scaling.
    """

    scaling: AppEngineScaling
    manual_scaling_instances: Optional[int] = None


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
