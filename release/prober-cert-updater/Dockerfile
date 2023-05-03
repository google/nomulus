# Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

# This Dockerfile updates the SSL certs used by the probers. It uses the builder
# image, and should be built after the builder in the release process. It also
# needs the :core:nomulus jar in this directory, which is the caller's
# responsibility to copy it here.

ARG PROJECT_ID
ARG TAG_NAME
FROM gcr.io/${PROJECT_ID}/builder:${TAG_NAME}

COPY nomulus.jar /
COPY rotate_prober_certs.sh /usr/local/bin

ENTRYPOINT [ "rotate_prober_certs.sh" ]
