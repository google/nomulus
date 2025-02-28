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

import { Directive, ElementRef, Input, effect } from '@angular/core';
import { UserDataService } from '../services/userData.service';

export enum RESTRICTED_ELEMENTS {
  REGISTRAR_ELEMENT,
  OTE,
  USERS,
  BULK_DELETE,
  SUSPEND,
}

export const DISABLED_ELEMENTS_PER_ROLE = {
  NONE: [
    RESTRICTED_ELEMENTS.REGISTRAR_ELEMENT,
    RESTRICTED_ELEMENTS.OTE,
    RESTRICTED_ELEMENTS.SUSPEND,
  ],
  SUPPORT_LEAD: [],
  SUPPORT_AGENT: [],
};

@Directive({
  selector: '[elementId]',
  standalone: false,
})
export class UserLevelVisibility {
  @Input() elementId!: RESTRICTED_ELEMENTS | null;

  constructor(
    private userDataService: UserDataService,
    private el: ElementRef
  ) {
    effect(this.processElement.bind(this));
  }

  processElement() {
    const globalRole = this.userDataService?.userData()?.globalRole || 'NONE';
    if (this.elementId === null) {
      return;
    }
    if (
      // @ts-ignore
      (DISABLED_ELEMENTS_PER_ROLE[globalRole] || []).includes(this.elementId)
    ) {
      this.el.nativeElement.style.display = 'none';
    } else {
      this.el.nativeElement.style.display = '';
    }
  }
}
