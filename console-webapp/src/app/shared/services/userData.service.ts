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

import { Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { BackendService } from './backend.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { GlobalLoader, GlobalLoaderService } from './globalLoader.service';

export interface UserData {
  isAdmin: boolean;
  globalRole: string;
  technicalDocsUrl: string;
}

@Injectable({
  providedIn: 'root',
})
export class UserDataService implements GlobalLoader {
  public userData?: UserData;
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
        this.userData = userData;
      })
    );
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading user data', undefined, {
      duration: 1500,
    });
  }
}
