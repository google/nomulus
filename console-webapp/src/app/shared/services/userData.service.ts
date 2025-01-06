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

import { Injectable, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, tap } from 'rxjs';
import { BackendService } from './backend.service';
import { GlobalLoader, GlobalLoaderService } from './globalLoader.service';

export interface UserData {
  globalRole: string;
  isAdmin: boolean;
  // TODO: provide passcode from back-end
  passcode?: string;
  productName: string;
  supportEmail: string;
  supportPhoneNumber: string;
  technicalDocsUrl: string;
  userRoles?: Map<string, string>;
}

@Injectable({
  providedIn: 'root',
})
export class UserDataService implements GlobalLoader {
  userData = signal<UserData | undefined>(undefined);
  constructor(
    private backend: BackendService,
    protected globalLoader: GlobalLoaderService,
    private _snackBar: MatSnackBar
  ) {
    this.getUserData().subscribe(() => {
      this.globalLoader.stopGlobalLoader(this);
    });
    this.globalLoader.startGlobalLoader(this);
  }

  getUserData(): Observable<UserData> {
    return this.backend.getUserData().pipe(
      tap((userData: UserData) => {
        this.userData.set(userData);
      })
    );
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading user data');
  }
}
