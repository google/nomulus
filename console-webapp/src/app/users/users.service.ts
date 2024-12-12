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
}

@Injectable()
export class UsersService {
  users = signal<User[]>([]);
  currentlyOpenUserEmail = signal<string>('');
  isNewUser: boolean = false;

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService
  ) {}

  fetchUsersForRegistrar(registrarId: string) {
    return this.backendService.getUsers(registrarId);
  }

  fetchUsers() {
    return this.backendService
      .getUsers(this.registrarService.registrarId())
      .pipe(
        tap((users: User[]) => {
          this.users.set(users);
        })
      );
  }

  createOrAddNewUser(maybeExistingUser: User) {
    return this.backendService
      .createUser(this.registrarService.registrarId(), maybeExistingUser)
      .pipe(
        tap((newUser: User) => {
          if (newUser) {
            this.users.set([...this.users(), newUser]);
            this.currentlyOpenUserEmail.set(newUser.emailAddress);
            this.isNewUser = true;
          }
        })
      );
  }

  deleteUser(user: User) {
    return this.backendService
      .deleteUser(this.registrarService.registrarId(), user)
      .pipe(switchMap((_) => this.fetchUsers()));
  }

  updateUser(updatedUser: User) {
    return this.backendService
      .updateUser(this.registrarService.registrarId(), updatedUser)
      .pipe(switchMap((_) => this.fetchUsers()));
  }
}
