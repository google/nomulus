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
import { Observable, Subscription, timeout } from 'rxjs';

export interface GlobalLoader {
  loadingTimeout: () => void;
}

@Injectable({
  providedIn: 'root',
})
export class GlobalLoaderService {
  private static readonly TIMEOUT_MS = 3000;
  private loaders = new Map<GlobalLoader, Subscription>();
  public isLoading: boolean = false;

  private syncLoading() {
    this.isLoading = this.loaders.size > 0;
  }

  startGlobalLoader(c: GlobalLoader) {
    const subscription = new Observable(() => {})
      .pipe(timeout(GlobalLoaderService.TIMEOUT_MS))
      .subscribe({
        error: () => {
          this.loaders.delete(c);
          c.loadingTimeout();
          this.syncLoading();
        },
      });
    this.loaders.set(c, subscription);
    this.syncLoading();
  }

  stopGlobalLoader(c: GlobalLoader) {
    this.loaders.get(c)?.unsubscribe();
    this.loaders.delete(c);
    this.syncLoading();
  }
}
