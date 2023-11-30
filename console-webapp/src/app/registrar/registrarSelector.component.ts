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

import { Component, OnInit } from '@angular/core';
import { RegistrarService } from './registrar.service';
import { BreakpointObserver } from '@angular/cdk/layout';
import { distinctUntilChanged } from 'rxjs';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 599px)';

@Component({
  selector: 'app-registrar-selector',
  templateUrl: './registrarSelector.component.html',
  styleUrls: ['./registrarSelector.component.scss'],
})
export class RegistrarSelectorComponent implements OnInit {
  protected isMobile: boolean = false;

  readonly breakpoint$ = this.breakpointObserver
    .observe([MOBILE_LAYOUT_BREAKPOINT])
    .pipe(distinctUntilChanged());

  constructor(
    protected registrarService: RegistrarService,
    protected breakpointObserver: BreakpointObserver
  ) {}

  ngOnInit(): void {
    this.breakpoint$.subscribe(() => this.breakpointChanged());
  }

  private breakpointChanged() {
    this.isMobile = this.breakpointObserver.isMatched(MOBILE_LAYOUT_BREAKPOINT);
  }
}
