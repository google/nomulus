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

import { Component, EventEmitter, Output } from '@angular/core';
import { BreakPointObserverService } from '../shared/services/breakPoint.service';

@Component({
  selector: 'app-header',
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.scss'],
  standalone: false,
})
export class HeaderComponent {
  private isNavOpen = false;

  constructor(protected breakpointObserver: BreakPointObserverService) {}

  @Output() toggleNavOpen = new EventEmitter<boolean>();

  toggleNavPane() {
    this.isNavOpen = !this.isNavOpen;
    this.toggleNavOpen.emit(this.isNavOpen);
  }

  logOut() {
    window.open('/console?gcp-iap-mode=CLEAR_LOGIN_COOKIE', '_self');
  }
}
