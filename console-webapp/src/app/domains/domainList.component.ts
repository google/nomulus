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

import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { Component, ViewChild, effect } from '@angular/core';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { Subject, debounceTime } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { Domain, DomainListService } from './domainList.service';
import { RegistryLockComponent } from './registryLock.component';
import { RegistryLockService } from './registryLock.service';

@Component({
  selector: 'app-domain-list',
  templateUrl: './domainList.component.html',
  styleUrls: ['./domainList.component.scss'],
})
export class DomainListComponent {
  public static PATH = 'domain-list';
  private readonly DEBOUNCE_MS = 500;

  displayedColumns: string[] = [
    'domainName',
    'creationTime',
    'registrationExpirationTime',
    'statuses',
    'registryLock',
    'actions',
  ];

  dataSource: MatTableDataSource<Domain> = new MatTableDataSource();
  isLoading = true;

  searchTermSubject = new Subject<string>();
  searchTerm?: string;

  pageNumber?: number;
  resultsPerPage = 50;
  totalResults?: number = 0;

  @ViewChild(MatPaginator, { static: true }) paginator!: MatPaginator;

  constructor(
    protected domainListService: DomainListService,
    protected registrarService: RegistrarService,
    protected registryLockService: RegistryLockService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      this.pageNumber = 0;
      this.totalResults = 0;
      if (this.registrarService.registrarId()) {
        this.loadLocks();
        this.reloadData();
      }
    });
  }

  ngOnInit() {
    this.dataSource.paginator = this.paginator;
    // Don't spam the server unnecessarily while the user is typing
    this.searchTermSubject
      .pipe(debounceTime(this.DEBOUNCE_MS))
      .subscribe((searchTermValue) => {
        this.reloadData();
      });
  }

  ngOnDestroy() {
    this.searchTermSubject.complete();
  }

  openRegistryLock(domainName: string) {
    this.domainListService.selectedDomain = domainName;
    this.domainListService.activeActionComponent = RegistryLockComponent;
  }

  loadLocks() {
    this.registryLockService.retrieveLocks().subscribe({
      error: (err: HttpErrorResponse) => {
        if (err.status !== HttpStatusCode.Forbidden) {
          // Some users may not have registry lock permissions and that's OK
          this._snackBar.open(err.message);
        }
      },
    });
  }

  isDomainLocked(domainName: string) {
    return this.registryLockService.domainsLocks.some(
      (d) => d.domainName === domainName
    );
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
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.message);
          this.isLoading = false;
        },
        next: (domainListResult) => {
          this.dataSource.data = this.domainListService.domainsList;
          this.totalResults = (domainListResult || {}).totalResults || 0;
          this.isLoading = false;
        },
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
