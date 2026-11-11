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
import { CommonModule } from '@angular/common';
import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  Component,
  effect,
  Inject,
  OnDestroy,
  OnInit,
  signal,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { debounceTime, filter, Subject, take } from 'rxjs';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SelectedRegistrarWrapper } from '../shared/components/selectedRegistrarWrapper/selectedRegistrarWrapper.component';
import { ForceFocusDirective } from '../shared/directives/forceFocus.directive';
import {
  BULK_ACTION_NAME,
  Domain,
  DomainListService,
} from './domainList.service';
import { RegistryLockComponent } from './registryLock.component';
import { RegistryLockService } from './registryLock.service';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogRef,
} from '@angular/material/dialog';
import {
  RESTRICTED_ELEMENTS,
  UserLevelVisibility,
} from '../shared/directives/userLevelVisiblity.directive';
import { CdkColumnDef } from '@angular/cdk/table';

interface DomainResponse {
  message: string;
  responseCode: string;
}

interface DomainData {
  [domain: string]: DomainResponse;
}

@Component({
  selector: 'app-response-dialog',
  imports: [MaterialModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      @for (line of data.content; track line) {
      <div>{{ line }}</div>
      }
    </mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResponseDialogComponent {
  constructor(
    public dialogRef: MatDialogRef<ReasonDialogComponent>,
    @Inject(MAT_DIALOG_DATA)
    public data: { title: string; content: string[] }
  ) {}

  onClose(): void {
    this.dialogRef.close();
  }
}

enum Operation {
  deleting = 'deleting',
  suspending = 'suspending',
  unsuspending = 'unsuspending',
}

@Component({
  selector: 'app-reason-dialog',
  imports: [FormsModule, MaterialModule],
  template: `
    <h2 mat-dialog-title>
      Please provide the (EPP) reason for {{ data.operation }} the domain(s):
    </h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" style="width:100%">
        <textarea matInput [(ngModel)]="reason" rows="4"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-button color="warn" (click)="onSave()" [disabled]="!reason">
        Save
      </button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReasonDialogComponent {
  reason: string = '';
  constructor(
    public dialogRef: MatDialogRef<ReasonDialogComponent>,
    @Inject(MAT_DIALOG_DATA)
    public data: { operation: Operation }
  ) {}

  onSave(): void {
    this.dialogRef.close(this.reason);
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}

@Component({
  selector: 'app-domain-list',
  imports: [
    CommonModule,
    FormsModule,
    MaterialModule,
    SelectedRegistrarWrapper,
    ForceFocusDirective,
    UserLevelVisibility,
  ],
  templateUrl: './domainList.component.html',
  styleUrls: ['./domainList.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CdkColumnDef],
})
export class DomainListComponent implements OnInit, OnDestroy {
  public static PATH = 'domain-list';
  private static SUSPENDED_STATUSES = [
    'SERVER_RENEW_PROHIBITED',
    'SERVER_TRANSFER_PROHIBITED',
    'SERVER_UPDATE_PROHIBITED',
    'SERVER_DELETE_PROHIBITED',
    'SERVER_HOLD',
  ];
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
  isLoading = signal(true);

  searchTermSubject = new Subject<string>();
  searchTerm?: string;

  pageNumber?: number;
  resultsPerPage = 50;
  totalResults = signal<number | undefined>(0);

  reason: string = '';

  operationResult = signal<DomainData | undefined>(undefined);

  constructor(
    protected domainListService: DomainListService,
    protected registrarService: RegistrarService,
    protected registryLockService: RegistryLockService,
    private _snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {
    effect(() => {
      const registrarId = this.registrarService.registrarId();
      untracked(() => {
        this.pageNumber = 0;
        this.totalResults.set(0);
        this.domainListService.domainsList.set([]);
        this.domainListService.activeActionComponent.set(null);
        this.dataSource.data = [];
        if (registrarId) {
          this.loadLocks();
          this.reloadData();
        }
      });
    });
  }

  ngOnInit() {
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
    this.domainListService.selectedDomain.set(domainName);
    this.domainListService.activeActionComponent.set(RegistryLockComponent);
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
    return this.registryLockService
      .domainsLocks()
      .some((d) => d.domainName === domainName);
  }

  reloadData() {
    this.isLoading.set(true);
    this.domainListService
      .retrieveDomains(
        this.pageNumber,
        this.resultsPerPage,
        this.totalResults(),
        this.searchTerm
      )
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.message);
          this.isLoading.set(false);
        },
        next: (domainListResult) => {
          this.dataSource.data = this.domainListService.domainsList();
          this.totalResults.set((domainListResult || {}).totalResults || 0);
          this.isLoading.set(false);
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

  getElementIdForBulkDelete() {
    return RESTRICTED_ELEMENTS.BULK_DELETE;
  }

  getElementIdForSuspendUnsuspend() {
    return RESTRICTED_ELEMENTS.SUSPEND;
  }

  getOperationMessage(domain: string) {
    const opResult = this.operationResult();
    if (opResult && opResult[domain]) return opResult[domain].message;
    return '';
  }

  isDomainUnsuspendable(domain: Domain) {
    return DomainListComponent.SUSPENDED_STATUSES.every((s) =>
      domain.statuses.includes(s)
    );
  }

  sendDeleteRequest(reason: string) {
    this.isLoading.set(true);
    this.domainListService
      .bulkDomainAction(
        this.selection.selected.map((d) => d.domainName),
        reason,
        this.registrarService.registrarId(),
        BULK_ACTION_NAME.DELETE
      )
      .pipe(take(1))
      .subscribe({
        next: (result: DomainData) => {
          this.isLoading.set(false);
          const successCount = Object.keys(result).filter((domainName) =>
            result[domainName].responseCode.toString().startsWith('1')
          ).length;
          const failureCount = Object.keys(result).length - successCount;
          this.dialog.open(ResponseDialogComponent, {
            data: {
              title: 'Domain Deletion Results',
              content: [
                `Successfully deleted - ${successCount} domain(s)`,
                `Failed to delete - ${failureCount} domain(s)`,
                failureCount
                  ? 'Some domains could not be deleted due to ongoing processes or server errors. Please check the table for more information.'
                  : 'Please check the table for more information.',
              ],
            },
          });
          this.selection.clear();
          this.operationResult.set(result);
          this.reloadData();
        },
        error: (err: HttpErrorResponse) => {
          this.isLoading.set(false);
          this._snackBar.open(err.error || err.message);
        },
      });
  }

  deleteSelectedDomains() {
    const dialogRef = this.dialog.open(ReasonDialogComponent, {
      data: {
        operation: Operation.deleting,
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

  sendSuspendUnsuspendRequest(
    domainName: string,
    reason: string,
    actionName: BULK_ACTION_NAME
  ) {
    this.isLoading.set(true);
    this.domainListService
      .bulkDomainAction(
        [domainName],
        reason,
        this.registrarService.registrarId(),
        actionName
      )
      .pipe(take(1))
      .subscribe({
        next: (result: DomainData) => {
          this.isLoading.set(false);
          if (result[domainName].responseCode.toString().startsWith('2')) {
            this._snackBar.open(result[domainName].message);
          } else {
            this.reloadData();
          }
        },
        error: (err: HttpErrorResponse) => {
          this.isLoading.set(false);
          this._snackBar.open(err.error || err.message);
        },
      });
  }
  onSuspendClick(domainName: string) {
    const dialogRef = this.dialog.open(ReasonDialogComponent, {
      data: {
        operation: Operation.suspending,
      },
    });

    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        filter((reason) => !!reason)
      )
      .subscribe((reason) => {
        this.sendSuspendUnsuspendRequest(
          domainName,
          reason,
          BULK_ACTION_NAME.SUSPEND
        );
      });
  }

  onUnsuspendClick(domainName: string) {
    const dialogRef = this.dialog.open(ReasonDialogComponent, {
      data: {
        operation: Operation.unsuspending,
      },
    });

    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        filter((reason) => !!reason)
      )
      .subscribe((reason) => {
        this.sendSuspendUnsuspendRequest(
          domainName,
          reason,
          BULK_ACTION_NAME.UNSUSPEND
        );
      });
  }
}
