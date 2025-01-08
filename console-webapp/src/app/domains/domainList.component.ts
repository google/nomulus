// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { SelectionModel } from '@angular/cdk/collections';
import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { Component, ViewChild, effect, Inject } from '@angular/core';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { Subject, debounceTime, take, filter } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { Domain, DomainListService } from './domainList.service';
import { RegistryLockComponent } from './registryLock.component';
import { RegistryLockService } from './registryLock.service';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogRef,
} from '@angular/material/dialog';

interface DomainResponse {
  message: string;
  responseCode: string;
}

interface DomainData {
  [domain: string]: DomainResponse;
}

@Component({
  selector: 'app-response-dialog',
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content [innerHTML]="data.content" />
    <mat-dialog-actions>
      <button mat-button (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
})
export class ResponseDialogComponent {
  constructor(
    public dialogRef: MatDialogRef<ReasonDialogComponent>,
    @Inject(MAT_DIALOG_DATA)
    public data: { title: string; content: string }
  ) {}

  onClose(): void {
    this.dialogRef.close();
  }
}

@Component({
  selector: 'app-reason-dialog',
  template: `
    <h2 mat-dialog-title>
      Please provide a reason for {{ data.operation }} the domain(s):
    </h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" style="width:100%">
        <textarea matInput [(ngModel)]="reason" rows="4"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-button color="warn" (click)="onDelete()" [disabled]="!reason">
        Delete
      </button>
    </mat-dialog-actions>
  `,
})
export class ReasonDialogComponent {
  reason: string = '';

  constructor(
    public dialogRef: MatDialogRef<ReasonDialogComponent>,
    @Inject(MAT_DIALOG_DATA)
    public data: { operation: 'deleting' | 'suspending' }
  ) {}

  onDelete(): void {
    this.dialogRef.close(this.reason);
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}

@Component({
  selector: 'app-domain-list',
  templateUrl: './domainList.component.html',
  styleUrls: ['./domainList.component.scss'],
})
export class DomainListComponent {
  public static PATH = 'domain-list';
  private readonly DEBOUNCE_MS = 500;
  isAllSelected = false;

  displayedColumns: string[] = [
    'select',
    'domainName',
    'creationTime',
    'registrationExpirationTime',
    'statuses',
    'registryLock',
    'actions',
  ];

  dataSource: MatTableDataSource<Domain> = new MatTableDataSource();
  selection = new SelectionModel<Domain>(true, [], undefined, this.isChecked());
  isLoading = true;

  searchTermSubject = new Subject<string>();
  searchTerm?: string;

  pageNumber?: number;
  resultsPerPage = 50;
  totalResults?: number = 0;

  reason: string = '';

  operationResult: DomainData | undefined;

  @ViewChild(MatPaginator, { static: true }) paginator!: MatPaginator;

  constructor(
    protected domainListService: DomainListService,
    protected registrarService: RegistrarService,
    protected registryLockService: RegistryLockService,
    private _snackBar: MatSnackBar,
    private dialog: MatDialog
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
    this.selection.clear();
    this.reloadData();
  }

  toggleAllRows() {
    if (this.isAllSelected) {
      this.selection.clear();
      this.isAllSelected = false;
      return;
    }

    this.selection.select(...this.dataSource.data);
    this.isAllSelected = true;
  }

  checkboxLabel(row?: Domain): string {
    if (!row) {
      return `${this.isAllSelected ? 'deselect' : 'select'} all`;
    }
    return `${this.selection.isSelected(row) ? 'deselect' : 'select'} row ${
      row.domainName
    }`;
  }

  private isChecked(): ((o1: Domain, o2: Domain) => boolean) | undefined {
    return (o1: Domain, o2: Domain) => {
      if (!o1.domainName || !o2.domainName) {
        return false;
      }

      return this.isAllSelected || o1.domainName === o2.domainName;
    };
  }

  getOperationMessage(domain: string) {
    if (this.operationResult && this.operationResult[domain])
      return this.operationResult[domain].message;
    return '';
  }

  sendDeleteRequest(reason: string) {
    this.isLoading = true;
    this.domainListService
      .deleteDomains(
        this.selection.selected,
        reason,
        this.registrarService.registrarId()
      )
      .pipe(take(1))
      .subscribe({
        next: (result: DomainData) => {
          this.isLoading = false;
          const successCount = Object.keys(result).filter((domainName) =>
            result[domainName].responseCode.toString().startsWith('1')
          ).length;
          const failureCount = Object.keys(result).length - successCount;
          this.dialog.open(ResponseDialogComponent, {
            data: {
              title: 'Domain Deletion Results',
              content: `Successfully deleted - ${successCount} domain(s)<br/>Failed to delete - ${failureCount} domain(s)<br/>${
                failureCount
                  ? 'Some domains could not be deleted due to ongoing processes or server errors. '
                  : ''
              }Please check the table for more information.`,
            },
          });
          this.selection.clear();
          this.operationResult = result;
          this.reloadData();
        },
        error: (err: HttpErrorResponse) =>
          this._snackBar.open(err.error || err.message),
      });
  }

  deleteSelectedDomains() {
    const dialogRef = this.dialog.open(ReasonDialogComponent, {
      data: {
        operation: 'deleting',
      },
    });

    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        filter((reason) => !!reason)
      )
      .subscribe(this.sendDeleteRequest.bind(this));
  }
}
