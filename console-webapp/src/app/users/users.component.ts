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

import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  effect,
  inject,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, takeUntil } from 'rxjs';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SelectedRegistrarWrapper } from '../shared/components/selectedRegistrarWrapper/selectedRegistrarWrapper.component';
import { UserDetailsComponent } from './userDetails.component';
import {
  AsyncState,
  User,
  UsersService,
  UsersViewState,
} from './users.service';
import { UsersListComponent } from './usersList.component';
import { MatSelectChange } from '@angular/material/select';
import { UserEditFormComponent } from './userEditForm.component';

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.scss'],
  imports: [
    MaterialModule,
    SelectedRegistrarWrapper,
    UsersListComponent,
    UserEditFormComponent,
    UserDetailsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [UsersService],
})
export class UsersComponent {
  private readonly destroyRef = inject(DestroyRef);
  private readonly registrarChanged$ = new Subject<void>();
  private readonly usersLoadCancelled$ = new Subject<void>();
  private readonly sourceRegistrarChanged$ = new Subject<void>();

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      const registrarId = registrarService.registrarId();
      untracked(() => {
        this.resetStateForRegistrarChange();
        if (registrarId) {
          this.loadUsers();
        }
      });
    });
  }

  /**
   * Cancels in-flight user queries, clears cached users, and exits any open
   * user detail or creation flow when switching to a different registrar.
   */
  private resetStateForRegistrarChange() {
    this.registrarChanged$.next();
    this.usersLoadCancelled$.next();
    this.sourceRegistrarChanged$.next();
    this.usersService.resetState();
  }

  private addExistingState(
    sourceRegistrarId = '',
    candidates: AsyncState<User[]> = { status: 'idle' }
  ): UsersViewState {
    return {
      kind: 'add-existing',
      sourceRegistrarId,
      candidates,
      selectedUser: null,
      submitting: false,
    };
  }

  openCreateNew() {
    this.sourceRegistrarChanged$.next();
    this.usersService.viewState.set({ kind: 'create-new', saving: false });
  }

  backToList() {
    this.sourceRegistrarChanged$.next();
    this.usersService.returnToList();
  }

  addExistingUser() {
    this.sourceRegistrarChanged$.next();
    this.usersService.viewState.set(this.addExistingState());
  }

  existingUserSelected(user: User) {
    this.usersService.viewState.update((state) =>
      state.kind === 'add-existing' ? { ...state, selectedUser: user } : state
    );
  }

  loadUsers() {
    this.usersLoadCancelled$.next();
    this.usersService.viewState.set({ kind: 'loading' });
    this.usersService
      .fetchUsers()
      .pipe(
        takeUntil(this.usersLoadCancelled$),
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => this.usersService.returnToList(),
        error: (err: HttpErrorResponse) => {
          const message = err.error || err.message;
          this._snackBar.open(message);
          this.usersService.viewState.set({ kind: 'error', message });
        },
      });
  }

  createNewUser(user: User) {
    const current = this.usersService.viewState();
    if (current.kind === 'create-new' && current.saving) {
      return;
    }
    this.usersService.viewState.set({ kind: 'create-new', saving: true });
    this.usersService
      .createNewUser(user)
      .pipe(
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (createdUser) => {
          if (createdUser) {
            this.usersService.openUserDetails(createdUser.emailAddress, true);
          } else {
            this.loadUsers();
          }
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
          this.usersService.viewState.set({
            kind: 'create-new',
            saving: false,
          });
        },
      });
  }

  openDetails(user: User) {
    this.usersService.openUserDetails(user.emailAddress, false);
  }

  onRegistrarSelectionChange(e: MatSelectChange) {
    this.sourceRegistrarChanged$.next();
    if (this.usersService.viewState().kind !== 'add-existing') {
      return;
    }
    const sourceRegistrarId: string = e.value || '';
    if (!sourceRegistrarId) {
      this.usersService.viewState.set(this.addExistingState());
      return;
    }
    this.usersService.viewState.set(
      this.addExistingState(sourceRegistrarId, { status: 'loading' })
    );
    const updateCandidates = (candidates: AsyncState<User[]>) =>
      this.usersService.viewState.update((state) =>
        state.kind === 'add-existing' &&
        state.sourceRegistrarId === sourceRegistrarId
          ? { ...state, candidates, selectedUser: null }
          : state
      );
    this.usersService
      .fetchUsersForRegistrar(sourceRegistrarId)
      .pipe(
        takeUntil(this.sourceRegistrarChanged$),
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (users) => updateCandidates({ status: 'loaded', data: users }),
        error: (err: HttpErrorResponse) => {
          const message = err.error || err.message;
          this._snackBar.open(message);
          updateCandidates({ status: 'error', message });
        },
      });
  }

  submitExistingUser() {
    const state = this.usersService.viewState();
    if (
      state.kind !== 'add-existing' ||
      !state.selectedUser ||
      state.submitting
    ) {
      return;
    }
    this.usersService.viewState.set({ ...state, submitting: true });
    this.usersService
      .addExistingUser(state.selectedUser)
      .pipe(
        takeUntil(this.registrarChanged$),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => this.loadUsers(),
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
          this.usersService.viewState.update((current) =>
            current.kind === 'add-existing'
              ? { ...current, submitting: false }
              : current
          );
        },
      });
  }
}
