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
  computed,
  DestroyRef,
  effect,
  inject,
  Inject,
  signal,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import {
  debounceTime,
  startWith,
  Subject,
  Subscription,
  switchMap,
  take,
  takeUntil,
} from 'rxjs';
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
    public dialogRef: MatDialogRef<ResponseDialogComponent>,
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
export class DomainListComponent {
  public static PATH = 'domain-list';
  private static readonly DEFAULT_PAGE_SIZE = 50;
  private static SUSPENDED_STATUSES = [
    'SERVER_RENEW_PROHIBITED',
    'SERVER_TRANSFER_PROHIBITED',
    'SERVER_UPDATE_PROHIBITED',
    'SERVER_DELETE_PROHIBITED',
    'SERVER_HOLD',
  ];
  private readonly DEBOUNCE_MS = 500;
  private readonly destroyRef = inject(DestroyRef);

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
  selection = new SelectionModel<Domain>(
    true,
    [],
    undefined,
    (o1: Domain, o2: Domain) =>
      Boolean(o1.domainName) && o1.domainName === o2.domainName
  );
  isLoading = signal(false);

  searchTermSubject = new Subject<string>();
  searchTerm = signal('');
  activeSearchTerm = signal('');

  pageNumber = signal(0);
  resultsPerPage = signal(DomainListComponent.DEFAULT_PAGE_SIZE);
  totalResults = signal<number | undefined>(undefined);

  selectedDomains = signal<Domain[]>([]);
  readonly selectedDomainNames = computed(
    () => new Set(this.selectedDomains().map((d) => d.domainName))
  );
  readonly isAllSelected = computed(() => {
    const rows = this.domainListService.domainsList();
    const selectedNames = this.selectedDomainNames();
    return (
      rows.length > 0 &&
      selectedNames.size === rows.length &&
      rows.every((row) => selectedNames.has(row.domainName))
    );
  });
  readonly isSomeSelected = computed(
    () => this.selectedDomains().length > 0 && !this.isAllSelected()
  );
  readonly isEmptyRegistrar = computed(
    () =>
      !this.isLoading() && this.totalResults() === 0 && !this.activeSearchTerm()
  );

  operationResult = signal<DomainData | undefined>(undefined);
  private readonly registrarChanged$ = new Subject<void>();
  private domainsSubscription?: Subscription;
  private locksSubscription?: Subscription;
  private bulkActionSubscription?: Subscription;
  private activeDialogRef?: MatDialogRef<ReasonDialogComponent>;

  constructor(
    protected domainListService: DomainListService,
    protected registrarService: RegistrarService,
    protected registryLockService: RegistryLockService,
    private _snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {
    this.selection.changed
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.selectedDomains.set([...this.selection.selected]));

    // Don't spam the server unnecessarily while the user is typing;
    // reset debounce whenever the active registrar changes.
    this.registrarChanged$
      .pipe(
        startWith(undefined),
        switchMap(() =>
          this.searchTermSubject.pipe(debounceTime(this.DEBOUNCE_MS))
        ),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((searchTermValue) => {
        if (!this.registrarService.registrarId()) {
          return;
        }
        this.selection.clear();
        this.operationResult.set(undefined);
        this.activeSearchTerm.set(searchTermValue);
        this.resetPaginationForReload(true);
        this.reloadData();
      });

    effect(() => {
      const registrarId = this.registrarService.registrarId();
      untracked(() => {
        this.resetStateForRegistrarChange();
        if (registrarId) {
          this.loadLocks();
          this.reloadData();
        }
      });
    });
  }

  /**
   * Cancels in-flight requests and clears registrar-scoped table, selection,
   * and service state so stale data cannot bleed across registrars.
   */
  private resetStateForRegistrarChange() {
    this.registrarChanged$.next();
    this.activeDialogRef?.close?.();
    this.activeDialogRef = undefined;
    this.isLoading.set(false);
    this.searchTerm.set('');
    this.activeSearchTerm.set('');
    this.pageNumber.set(0);
    this.resultsPerPage.set(DomainListComponent.DEFAULT_PAGE_SIZE);
    this.totalResults.set(undefined);
    this.selection.clear();
    this.selectedDomains.set([]);
    this.operationResult.set(undefined);
    this.dataSource.data = [];
    this.domainListService.resetState();
    this.registryLockService.domainsLocks.set([]);
  }

  openRegistryLock(domainName: string) {
    this.domainListService.selectedDomain.set(domainName);
    this.domainListService.activeActionComponent.set(RegistryLockComponent);
  }

  loadLocks() {
    this.locksSubscription?.unsubscribe();
    this.locksSubscription = this.registryLockService
      .retrieveLocks()
      .pipe(
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
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
    this.domainsSubscription?.unsubscribe();
    this.isLoading.set(true);
    this.domainsSubscription = this.domainListService
      .retrieveDomains(
        this.pageNumber(),
        this.resultsPerPage(),
        this.totalResults(),
        this.activeSearchTerm() || undefined
      )
      .pipe(
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.message);
          this.isLoading.set(false);
        },
        next: (domainListResult) => {
          this.dataSource.data = this.domainListService.domainsList();
          this.totalResults.set(domainListResult?.totalResults || 0);
          this.isLoading.set(false);
        },
      });
  }

  onSearchInput(searchTerm: string) {
    this.searchTerm.set(searchTerm);
    this.searchTermSubject.next(searchTerm);
  }

  onPageChange(event: PageEvent) {
    this.pageNumber.set(event.pageIndex);
    this.resultsPerPage.set(event.pageSize);
    this.selection.clear();
    this.reloadData();
  }

  isRowSelected(row: Domain): boolean {
    return this.selectedDomainNames().has(row.domainName);
  }

  toggleAllRows() {
    if (this.isAllSelected()) {
      this.selection.clear();
      return;
    }

    this.selection.select(...this.dataSource.data);
  }

  checkboxLabel(row?: Domain): string {
    if (!row) {
      return `${this.isAllSelected() ? 'deselect' : 'select'} all`;
    }
    return `${this.isRowSelected(row) ? 'deselect' : 'select'} row ${
      row.domainName
    }`;
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

  private resetPaginationForReload(resetPageNumber = false) {
    this.domainListService.clearPaginationCursor();
    if (resetPageNumber) {
      this.pageNumber.set(0);
    }
    this.totalResults.set(undefined);
  }

  private openReasonDialog(
    operation: Operation,
    onReason: (reason: string) => void
  ) {
    this.activeDialogRef?.close?.();
    const dialogRef = this.dialog.open(ReasonDialogComponent, {
      data: { operation },
    });
    this.activeDialogRef = dialogRef;
    dialogRef
      .afterClosed()
      .pipe(
        take(1),
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((reason) => {
        if (this.activeDialogRef === dialogRef) {
          this.activeDialogRef = undefined;
        }
        if (reason) {
          onReason(reason);
        }
      });
  }

  sendDeleteRequest(reason: string) {
    const domains = this.selection.selected.map((d) => d.domainName);
    if (domains.length === 0) {
      return;
    }
    this.bulkActionSubscription?.unsubscribe();
    this.isLoading.set(true);
    this.bulkActionSubscription = this.domainListService
      .bulkDomainAction(
        domains,
        reason,
        this.registrarService.registrarId(),
        BULK_ACTION_NAME.DELETE
      )
      .pipe(
        take(1),
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
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
          this.resetPaginationForReload(true);
          this.reloadData();
        },
        error: (err: HttpErrorResponse) => {
          this.isLoading.set(false);
          this._snackBar.open(err.error || err.message);
        },
      });
  }

  deleteSelectedDomains() {
    if (this.selectedDomains().length === 0) {
      return;
    }
    this.openReasonDialog(Operation.deleting, (reason) =>
      this.sendDeleteRequest(reason)
    );
  }

  sendSuspendUnsuspendRequest(
    domainName: string,
    reason: string,
    actionName: BULK_ACTION_NAME
  ) {
    this.bulkActionSubscription?.unsubscribe();
    this.isLoading.set(true);
    this.bulkActionSubscription = this.domainListService
      .bulkDomainAction(
        [domainName],
        reason,
        this.registrarService.registrarId(),
        actionName
      )
      .pipe(
        take(1),
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (result: DomainData) => {
          this.isLoading.set(false);
          const domainResult = result?.[domainName];
          if (domainResult?.responseCode.toString().startsWith('2')) {
            this._snackBar.open(domainResult.message);
          } else {
            this.resetPaginationForReload();
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
    this.openReasonDialog(Operation.suspending, (reason) =>
      this.sendSuspendUnsuspendRequest(
        domainName,
        reason,
        BULK_ACTION_NAME.SUSPEND
      )
    );
  }

  onUnsuspendClick(domainName: string) {
    this.openReasonDialog(Operation.unsuspending, (reason) =>
      this.sendSuspendUnsuspendRequest(
        domainName,
        reason,
        BULK_ACTION_NAME.UNSUSPEND
      )
    );
  }
}
