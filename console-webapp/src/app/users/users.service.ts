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

import { Injectable, signal } from '@angular/core';
import { switchMap, tap } from 'rxjs';
import { RegistrarService } from '../registrar/registrar.service';
import { BackendService } from '../shared/services/backend.service';

export type AsyncState<T> =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'loaded'; readonly data: T }
  | { readonly status: 'error'; readonly message: string };

export const roleToDescription = (role: string) => {
  if (!role) return 'N/A';
  else if (role === 'ACCOUNT_MANAGER') {
    return 'Viewer';
  }
  return 'Editor';
};

export interface CreateAutoTimestamp {
  creationTime: string;
}

export interface User {
  emailAddress: string;
  role: string;
  password?: string;
  registryLockEmailAddress?: string;
}

/**
 * Explicit UI state machine for the Users Management feature.
 * Replaces orthogonal booleans (`isNew`, `selectingExistingUser`, `isNewUser`)
 * and sentinel email strings so illegal combinations cannot be represented.
 */
export type UsersViewState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'error'; readonly message: string }
  | { readonly kind: 'list' }
  | { readonly kind: 'create-new'; readonly saving: boolean }
  | {
      readonly kind: 'add-existing';
      readonly sourceRegistrarId: string;
      readonly candidates: AsyncState<User[]>;
      readonly selectedUser: User | null;
      readonly submitting: boolean;
    }
  | {
      readonly kind: 'detail';
      readonly email: string;
      readonly isNewlyCreated: boolean;
    };

@Injectable()
export class UsersService {
  readonly users = signal<User[]>([]);
  readonly viewState = signal<UsersViewState>({ kind: 'list' });

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  resetState() {
    this.users.set([]);
    this.viewState.set({ kind: 'list' });
  }

  returnToList() {
    this.viewState.set({ kind: 'list' });
  }

  openUserDetails(emailAddress: string, isNewlyCreated = false) {
    this.viewState.set({
      kind: 'detail',
      email: emailAddress,
      isNewlyCreated,
    });
  }

  fetchUsersForRegistrar(registrarId: string) {
    return this.backendService.getUsers(registrarId);
  }

  fetchUsers(registrarId = this.registrarService.registrarId()) {
    return this.backendService.getUsers(registrarId).pipe(
      tap((users: User[]) => {
        this.users.set(users);
      })
    );
  }

  createNewUser(user: User) {
    return this.backendService
      .createUser(this.registrarService.registrarId(), user)
      .pipe(
        tap((newUser: User) => {
          if (newUser) {
            this.users.update((existing) => [...existing, newUser]);
          }
        })
      );
  }

  addExistingUser(user: User) {
    return this.backendService.createUser(
      this.registrarService.registrarId(),
      user
    );
  }

  deleteUser(user: User) {
    const registrarId = this.registrarService.registrarId();
    return this.backendService
      .deleteUser(registrarId, user)
      .pipe(switchMap(() => this.fetchUsers(registrarId)));
  }

  updateUser(updatedUser: User) {
    const registrarId = this.registrarService.registrarId();
    return this.backendService
      .updateUser(registrarId, updatedUser)
      .pipe(switchMap(() => this.fetchUsers(registrarId)));
  }
}
