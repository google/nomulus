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

import { BreakpointObserver } from '@angular/cdk/layout';
import { Injectable, signal } from '@angular/core';
import { distinctUntilChanged } from 'rxjs';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 560px)';
const TABLET_LAYOUT_BREAKPOINT = '(max-width: 768px)';

@Injectable({
  providedIn: 'root',
})
export class BreakPointObserverService {
  isMobileView = signal<boolean>(false);
  isTabletView = signal<boolean>(false);

  readonly breakpoint$ = this.breakpointObserver
    .observe([MOBILE_LAYOUT_BREAKPOINT, TABLET_LAYOUT_BREAKPOINT])
    .pipe(distinctUntilChanged());

  constructor(protected breakpointObserver: BreakpointObserver) {
    this.breakpoint$.subscribe(() => this.breakpointChanged());
  }

  private breakpointChanged() {
    this.isMobileView.set(
      this.breakpointObserver.isMatched(MOBILE_LAYOUT_BREAKPOINT)
    );
    this.isTabletView.set(
      this.breakpointObserver.isMatched(TABLET_LAYOUT_BREAKPOINT)
    );
  }
}
