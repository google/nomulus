// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import { Injectable } from '@angular/core';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import { tap } from 'rxjs';

export interface CreateAutoTimestamp {
  creationTime: string;
}

export interface Domain {
  creationTime: CreateAutoTimestamp;
  currentSponsorRegistrarId: string;
  domainName: string;
  registrationExpirationTime: string;
  statuses: string[];
}

export interface DomainListResult {
  checkpointTime: string;
  domains: Domain[];
  totalResults: number;
}

@Injectable()
export class DomainListService {
  checkpointTime?: string;

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  retrieveDomains(
    pageNumber?: number,
    resultsPerPage?: number,
    totalResults?: number
  ) {
    return this.backendService
      .getDomains(
        this.registrarService.activeRegistrarId,
        this.checkpointTime,
        pageNumber,
        resultsPerPage,
        totalResults
      )
      .pipe(
        tap((domainListResult: DomainListResult) => {
          this.checkpointTime = domainListResult.checkpointTime;
        })
      );
  }
}
