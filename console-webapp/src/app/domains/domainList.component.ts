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

import { Component, ViewChild } from '@angular/core';
import { MatTableDataSource } from '@angular/material/table';
import { BackendService } from '../shared/services/backend.service';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { RegistrarService } from '../registrar/registrar.service';
import { Domain, DomainListService } from './domainList.service';
import { Subject, debounceTime } from 'rxjs';

@Component({
  selector: 'app-domain-list',
  templateUrl: './domainList.component.html',
  styleUrls: ['./domainList.component.scss'],
  providers: [DomainListService],
})
export class DomainListComponent {
  public static PATH = 'domain-list';
  private readonly DEBOUNCE_MS = 1000;

  displayedColumns: string[] = [
    'domainName',
    'creationTime',
    'registrationExpirationTime',
    'statuses',
  ];

  dataSource: MatTableDataSource<Domain> = new MatTableDataSource();
  isLoading = true;

  searchTermSubject = new Subject<string>();
  searchTerm?: string;

  pageNumber?: number;
  resultsPerPage = 50;
  totalResults?: number;

  @ViewChild(MatPaginator, { static: true }) paginator!: MatPaginator;

  constructor(
    private backendService: BackendService,
    private domainListService: DomainListService,
    private registrarService: RegistrarService
  ) {}

  ngOnInit() {
    this.dataSource.paginator = this.paginator;
    // Don't spam the server unnecessarily while the user is typing
    this.searchTermSubject
      .pipe(debounceTime(this.DEBOUNCE_MS))
      .subscribe((searchTermValue) => {
        this.reloadData();
      });
    this.reloadData();
  }

  ngOnDestroy() {
    this.searchTermSubject.complete();
  }

  reloadData() {
    this.isLoading = true;
    this.domainListService
      .retrieveDomains(
        this.pageNumber,
        this.resultsPerPage,
        this.totalResults,
        this.searchTerm
      )
      .subscribe((domainListResult) => {
        this.dataSource.data = domainListResult.domains;
        this.totalResults = domainListResult.totalResults;
        this.isLoading = false;
      });
  }

  sendInput() {
    this.searchTermSubject.next(this.searchTerm!);
  }

  onPageChange(event: PageEvent) {
    this.pageNumber = event.pageIndex;
    this.resultsPerPage = event.pageSize;
    this.reloadData();
  }
}
