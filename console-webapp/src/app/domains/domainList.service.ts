// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import { Injectable, Type } from '@angular/core';
import { tap } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { BackendService } from '../shared/services/backend.service';

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

@Injectable({
  providedIn: 'root',
})
export class DomainListService {
  checkpointTime?: string;
  selectedDomain?: string;
  public activeActionComponent: Type<any> | null = null;
  public domainsList: Domain[] = [];

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  retrieveDomains(
    pageNumber?: number,
    resultsPerPage?: number,
    totalResults?: number,
    searchTerm?: string
  ) {
    return this.backendService
      .getDomains(
        this.registrarService.registrarId(),
        this.checkpointTime,
        pageNumber,
        resultsPerPage,
        totalResults,
        searchTerm
      )
      .pipe(
        tap((domainListResult: DomainListResult) => {
          this.checkpointTime = domainListResult?.checkpointTime;
          this.domainsList = domainListResult?.domains;
        })
      );
  }
}
